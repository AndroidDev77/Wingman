# Third-party notices: Wingman Hinge connector (WING-029)

## hinge-rs

- Project: hinge-rs, https://github.com/f0rr0/hinge-rs
- Version used as the protocol reference: commit `cffe397bd5905072a619fb041d6fe779a543a2f3`
  ("fix: restore app 10.0.0 chat and profile reads (#29)", 2026-10-08)
- License: MIT OR Apache-2.0 (Wingman uses it under the MIT option)

The Kotlin code in this package (`com.wingman.integrations.hinge`) is an independent
reimplementation of the protocol that hinge-rs documents: the login sequence (install
registration, SMS code, optional email code, chat token), the request headers and query
parameters it sends, the Sendbird REST reads, and the Sendbird WebSocket `LOGI` / `MESG` /
`EROR` handling, including the send-confirmation-by-echo rule and the "never resend on a timeout"
rule. No Rust source was copied or mechanically translated, and no hinge-rs file is vendored.
Protocol facts (endpoint paths, header and parameter names, frame names) were taken from it, so
its notice is reproduced below.

The client identity values (app version, build number, Sendbird application id, SDK version) are
not part of this source tree. They are supplied by the experimental build configuration
(`docs/integrations/hinge.md`).

```
MIT License

Copyright (c) 2026 hinge-rs contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Hinge and Sendbird are trademarks of their respective owners. Neither is affiliated with,
endorses, or supports Wingman or this connector.
