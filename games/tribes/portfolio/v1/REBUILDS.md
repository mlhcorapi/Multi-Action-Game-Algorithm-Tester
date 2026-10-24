# Rebuilds of the published Tribes portfolio

The portfolio in this directory was built on the R7615 on 2026-08-28 by the original builder,
`BuildV6Portfolio` (its log, `logs/A1_portfolio.log`, is archived with the R7615 currency of the time;
see `docs/ARCHIVE.md`). Each row below is an independent rebuild with
`magat portfolio build`, followed by `magat portfolio freeze` and
`magat portfolio verify --published games/tribes/portfolio/v1 --plan 8`.

A rebuild that matches has the same identity, every manifest row equal (id, attempt, seed, phase,
branching, every `describe` fact), the same development and holdout membership when its split is frozen
from scratch, and identical `emcts` plans at 8,000 units from its own scenario files and the published
ones. The scenario files themselves are not byte-identical between JVM runs, because serialized hash
maps iterate in identity-hash order. That is why the plans are compared.

| Date | Machine | OS, JDK | Threads | Wall-clock | Identity | Rows | Split | Plans (8) | MAGAT |
|---|---|---|---|---|---|---|---|---|---|
| 2026-08-28 | R7615, AMD EPYC 9534 (64 cores) | Linux, JDK not recorded | 48 | 5.4 min | `ec243fd7...` | (original) | (original) | (original) | the original builder |
| 2026-10-04 | Intel Core i9-14900HX laptop (24 cores) | Windows 11, JDK 26 (`--release 11`) | 20 | 15.5 min, sharing the machine with other jobs | `ec243fd7...` | 400/400 equal | equal | equal | 0.1.0-m2 |
| 2026-10-09 | Intel Core i9-14900HX laptop (24 cores) | Windows 11, JDK 26 (`--release 11`) | 20 | 8.7 min, alone, cores capped at 4,000 MHz | `ec243fd7...` | 400/400 equal | equal | equal | 0.1.0-m6, engine `828b130` (faster legal-action generation) |

Every rebuild's sources consumed exactly the attempts the R7615 did: single through attempt 36, dual through
attempt 108. The progress lines match the R7615 log line for line.
