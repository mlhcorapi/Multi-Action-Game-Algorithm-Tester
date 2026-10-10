# Time levels: tribes

Measured 2026-10-10T15:07:27 on AMD EPYC 9534 64-Core Processor | 128 logical / 64 physical (SMT x2) | 1 NUMA node(s) | maxHeap 4096 MB | reserve 2 | worker ceiling 62, engine `828b13051d`, MAGAT `dd4ae73105`.
Reference arm `emcts`, 64 positions spread over the portfolio, one turn each, single-threaded; initialization in-budget.

| Time | Units | Found (before rounding) | Mean | Off target | Median | Quartiles | Range | Initialization |
|---|---|---|---|---|---|---|---|---|
| 500 ms | **9,500** | 9,619 | 506 ms | +1.1% | 389 ms | 276-569 ms | 105-1,924 ms | 160 ms |
| 1,000 ms | **20,000** | 20,015 | 1,006 ms | +0.6% | 796 ms | 583-1,190 ms | 227-2,842 ms | 158 ms |
| 5,000 ms | **101,500** | 101,499 | 5,078 ms | +1.6% | 4,163 ms | 3,059-6,077 ms | 1,169-13,400 ms | 159 ms |

| Iteration | Units | Off target |
|---|---|---|
| 1 | 12,830, 25,660, 128,299 | +25.4%, +22.5%, +24.0% |
| 2 | 10,231, 20,944, 103,463 | +6.4%, +4.6%, +1.9% |
| 3 | 9,619, 20,015, 101,499 | +2.2%, +1.0%, +2.7% |
| 4 | 9,500, 20,000, 101,500 | +1.1%, +0.6%, +1.6% |

Converged: every level within 5% before rounding to 500 units. Took 64 min.
