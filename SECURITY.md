# Security policy

Thank you for helping keep CallCtrl and its users safe.

## Reporting a problem

**Please don't report security problems in a public issue.** Report them privately instead:

1. Go to the [Security tab](https://github.com/VoidedKN0X/CallCtrl/security) of this repo.
2. Click **Report a vulnerability** and describe what you found, how to reproduce it, and which version of CallCtrl you used.

Only the maintainer can see the report. I'll reply as soon as I can, work on a fix, and credit you in the changelog if you'd like.

## Supported versions

Only the latest release gets security fixes. Please update before reporting, and check the [changelog](CHANGELOG.md) to see whether it's already fixed.

## What counts

CallCtrl has no server and no internet permission: everything stays on your phone. So these count:

- Any way for CallCtrl to send your calls, call history or contacts off the phone
- Another app being able to make CallCtrl place, answer, end or block calls without you
- The call screen showing anything other than the call itself while the app lock is on
- Ways around the app lock, or protected screens showing up in screenshots when they shouldn't
- Weaknesses in the app lock PIN
- Anything that lets someone run code or read files they shouldn't

## How releases are protected

Every release is built from this repo by GitHub Actions and signed with the same release key. Its SHA-256 certificate fingerprint is:

```
80:98:04:45:55:FD:7F:38:11:BB:9D:E7:39:AC:AC:33:77:8D:22:B2:DA:B3:C3:5D:9D:5E:8C:B9:68:C5:1C:13
```

The [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) only accepts APKs signed with this key.
