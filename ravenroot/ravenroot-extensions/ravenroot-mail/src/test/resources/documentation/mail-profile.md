# Mail profile deployment contract fixture

The profile contains ten mandatory semicolon-separated fields followed by an optional
`allowedReplyTo` field.

Conformant value example

```
smtp.example.test;587;STARTTLS;false;mailer;primary;ops@example.test;billing@example.test,ops@example.test;X-Trace,X-Priority;4;replies@example.test
```
