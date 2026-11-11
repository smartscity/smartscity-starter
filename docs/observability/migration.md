# Observability migration

This is the first unreleased implementation. Add one starter matching the application Boot generation and the required optional exporter dependencies. No ID generator configuration, worker allocation, encoding or schema changes are needed.

| Existing behavior | Opt-in change |
|---|---|
| Arbitrary application logging pattern | Install the supplied Logback encoder to use the bracketed/double-pipe format |
| Existing trace backend/provider | Retain ownership; the adapter reuses the provider rather than installing another tracer |
| Standard spanId convention | Remains unchanged internally; log spanid is parent and cspanid is current |
| Historical Didi headers/hintcode behavior | Not reverse-engineered; W3C propagation is the supported contract |
| Micrometer registry | Retain existing meters and supplied registry ownership |
| UDP native auto-export | When Smartscity owns UDP, its bounded line sink replaces the native network sender; do not configure duplicate export |

Body capture is off by default. Enabling masking does not make every arbitrary string safe; configure literal/field/path rules appropriate to the application. Do not change a log collector parser before testing the new format against representative sanitized records.
