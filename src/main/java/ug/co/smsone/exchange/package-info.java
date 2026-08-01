/**
 * The Data Exchange Platform: import/export as durable, resumable, record-oriented JOBS — never
 * long-running requests (docs/reusable-data-exchange-platform-guidelines.md is the spec). The
 * module owns jobs, formats, streaming, layered validation, progress and error reports; it is
 * domain-agnostic by construction — business modules implement the {@code ExchangeHandler} port
 * and keep every business rule (the members handler drives the same {@code MemberService} the REST
 * surface uses). Files stay behind the files port; every artifact a job touches — source, error
 * report, export result — is registered as a document. Processing is at-least-once per record and
 * handlers must be idempotent: a reclaimed job resumes from its last committed batch offset, so
 * the records of the in-flight batch replay.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Exchange")
package ug.co.smsone.exchange;
