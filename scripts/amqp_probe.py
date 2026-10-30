#!/usr/bin/env python3
"""AMQP-direct probe for Phase 9. Subcommands: publish, capture, count, peek, purge.
Talks AMQP, not the management HTTP API (which lags the broker and caused flaky e2e runs in chat-service)."""
import argparse
import json
import os
import sys
import time

import pika


def s(v):
    return v.decode() if isinstance(v, bytes) else v


def connect():
    creds = pika.PlainCredentials(os.environ["RABBITMQ_USERNAME"], os.environ["RABBITMQ_PASSWORD"])
    params = pika.ConnectionParameters(host=os.environ.get("RABBITMQ_HOST", "rabbitmq"),
                                       port=int(os.environ.get("RABBITMQ_PORT", "5672")),
                                       virtual_host=os.environ.get("RABBITMQ_VHOST", "/"),
                                       credentials=creds, connection_attempts=5, retry_delay=1)
    return pika.BlockingConnection(params)


def publish(a):
    conn = connect()
    ch = conn.channel()
    ch.confirm_delivery()
    props = pika.BasicProperties(message_id=a.message_id, content_type="application/json", delivery_mode=2)
    ch.basic_publish(exchange=a.exchange, routing_key=a.routing_key, body=a.body.encode(), properties=props, mandatory=True)
    print(f"published {a.routing_key} message_id={a.message_id}")
    conn.close()


def capture(a):
    conn = connect()
    ch = conn.channel()
    q = ch.queue_declare(queue="", exclusive=True, auto_delete=True).method.queue
    ch.queue_bind(queue=q, exchange=a.exchange, routing_key=a.binding)
    open(os.path.join(a.out_dir, "ready"), "w").close()      # the bind exists: safe to trigger the publish now
    got, deadline = [], time.time() + a.timeout
    while len(got) < a.count and time.time() < deadline:
        method, props, body = ch.basic_get(queue=q, auto_ack=True)
        if method is None:
            time.sleep(0.2)
            continue
        got.append({"exchange": method.exchange, "routing_key": method.routing_key,
                    "properties": {"message_id": props.message_id, "content_type": props.content_type,
                                   "delivery_mode": props.delivery_mode, "app_id": props.app_id, "type": props.type},
                    "body": json.loads(body)})
    for i, event in enumerate(got, 1):
        path = os.path.join(a.out_dir, f"event_{i}.json")
        with open(path, "w") as f:
            json.dump(event, f, indent=2)
        print(f"captured {event['routing_key']} message_id={event['properties']['message_id']} -> event_{i}.json")
    conn.close()
    sys.exit(0 if len(got) == a.count else 1)


def count(a):
    conn = connect()
    q = conn.channel().queue_declare(queue=a.queue, passive=True)
    print(f"{a.queue} messages={q.method.message_count} consumers={q.method.consumer_count}")
    conn.close()


def peek(a):
    conn = connect()
    ch = conn.channel()
    method, props, body = ch.basic_get(queue=a.queue, auto_ack=False)
    if method is None:
        print("empty")
    else:
        deaths = [{"count": d.get("count"), "reason": s(d.get("reason")), "queue": s(d.get("queue"))}
                  for d in (props.headers or {}).get("x-death", [])]
        print(json.dumps({"message_id": props.message_id, "x-death": deaths, "body": body.decode(errors="replace")}))
        ch.basic_nack(method.delivery_tag, requeue=True)       # peek only: leave it in the queue
    conn.close()


def purge(a):
    conn = connect()
    n = conn.channel().queue_purge(queue=a.queue).method.message_count
    print(f"purged {n} from {a.queue}")
    conn.close()


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    x = sub.add_parser("publish"); x.add_argument("--exchange", required=True); x.add_argument("--routing-key", required=True)
    x.add_argument("--message-id", required=True); x.add_argument("--body", required=True); x.set_defaults(fn=publish)
    x = sub.add_parser("capture"); x.add_argument("--exchange", required=True); x.add_argument("--binding", required=True)
    x.add_argument("--count", type=int, default=1); x.add_argument("--timeout", type=int, default=30)
    x.add_argument("--out-dir", default="/out"); x.set_defaults(fn=capture)
    for name, fn in (("count", count), ("peek", peek), ("purge", purge)):
        x = sub.add_parser(name); x.add_argument("--queue", required=True); x.set_defaults(fn=fn)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
