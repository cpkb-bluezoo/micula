# micula

Brotli codec in Java.

*Micula* is Latin for “small crumb”; *brotli* is German for a small piece of
bread. This library is a pure-Java, production-grade encoder and decoder for
the [RFC 7932](https://www.rfc-editor.org/rfc/rfc7932) Brotli compressed data
format.

## Features

- **Pure Java** — no JNI, no native libraries
- **NIO-first** — `ByteBuffer` only; no `InputStream` / `OutputStream`
- **Event-driven** — push compressed bytes via `receive(ByteBuffer)`; receive
  reconstructed output and optional LZ77 command events through a handler
- **Incremental** — chunk-invariant decoding; resume mid-symbol across
  buffer boundaries
- **RFC 7932** — raw Brotli streams (not Google’s large-window extensions)

## Decoding

```java
BrotliDecoder decoder = new BrotliDecoder();
decoder.setHandler(new BrotliDefaultHandler() {
    @Override
    public void content(ByteBuffer data, boolean end) throws BrotliException {
        // data is valid only during this call
    }
});

ByteBuffer buffer = ByteBuffer.allocate(8192);
while (channel.read(buffer) > 0) {
    buffer.flip();
    decoder.receive(buffer);
    buffer.compact();
}
decoder.close();
```

## Encoding

```java
BrotliEncoder encoder = new BrotliEncoder(new ChannelBrotliSink(outChannel));
encoder.setQuality(1);   // 0 = uncompressed metablocks; 1–2 = LZ77 + Huffman
encoder.setWindowBits(22);
encoder.receive(uncompressed);
encoder.close();
```

## Build

```bash
ant build
ant test
```

Requires JDK 21+.

## License

GNU Lesser General Public License version 2.1 (see [LICENSE](LICENSE)).
