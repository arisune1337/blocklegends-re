#!/usr/bin/env python3
# Spawn net.blocklegends under frida (via forwarded frida-server) and inject scripts.
# usage: python fdrive.py script1.js script2.js ...
import frida, sys, time

PKG = 'net.blocklegends'
dev = frida.get_device_manager().add_remote_device('127.0.0.1:27042')
print('[drive] device:', dev)

pid = dev.spawn([PKG])
print('[drive] spawned pid', pid)
session = dev.attach(pid)

def on_message(message, data):
    t = message.get('type')
    if t == 'send':
        print('[js]', message.get('payload'), flush=True)
    elif t == 'log':
        print('[log]', message.get('payload'), flush=True)
    elif t == 'error':
        print('[js-error]', message.get('stack') or message.get('description'), flush=True)

for path in sys.argv[1:]:
    src = open(path, encoding='utf-8').read()
    sc = session.create_script(src)
    sc.on('message', on_message)
    sc.load()
    print('[drive] loaded', path)

dev.resume(pid)
print('[drive] resumed', pid)

# keep alive
try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    pass
