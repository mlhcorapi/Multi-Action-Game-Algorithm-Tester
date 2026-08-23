# Stage 1 — biased mutation results

Positive MeanDiff = the config finds BETTER final sequences than Baseline on the
same (scenario, visit) seeds. CI is a scenario-clustered bootstrap.

## 40000cu

| Config | Pairs | MeanDiff [CI] | RelDiff | WinRate |
|---|---|---|---|---|
| Bias_e25 | 589 | **+83.5 [+49.5, +124.0]** | +15.491 | 0.753 |
| Short15_e25 | 582 | **+188.1 [+136.4, +247.3]** | +28.798 | 0.832 |

## 160000cu

| Config | Pairs | MeanDiff [CI] | RelDiff | WinRate |
|---|---|---|---|---|
| Bias_e10 | 574 | **+160.8 [+112.7, +208.8]** | +13.673 | 0.814 |
| Bias_e25 | 577 | **+145.3 [+100.7, +192.5]** | +13.908 | 0.810 |
| Bias_e50 | 576 | **+134.0 [+99.4, +172.1]** | +12.153 | 0.837 |
| Bias_e75 | 580 | **+70.9 [+45.8, +98.4]** | +9.601 | 0.765 |
| Coarse_e25 | 579 | **+169.0 [+123.9, +219.2]** | +13.626 | 0.869 |
| NoRemoval_e25 | 575 | **+98.5 [+69.0, +132.0]** | +13.186 | 0.771 |
| PerBurn_e25 | 575 | **+165.1 [+121.7, +211.3]** | +13.223 | 0.839 |
| Short15_e25 | 572 | **+222.0 [+155.8, +294.3]** | +17.304 | 0.812 |
| Short5_e25 | 578 | **+243.4 [+174.3, +316.8]** | +19.056 | 0.812 |
| Shortlist15 | 575 | **+68.4 [+43.0, +93.6]** | +8.424 | 0.707 |

## 640000cu

| Config | Pairs | MeanDiff [CI] | RelDiff | WinRate |
|---|---|---|---|---|
| Bias_e25 | 342 | **+171.0 [+122.7, +223.0]** | +8.691 | 0.849 |
| Short15_e25 | 342 | **+224.0 [+149.5, +300.4]** | +10.312 | 0.805 |

## Stage-2 groundwork: pair-interaction reliability (Baseline seqlogs)

- 40000cu: split-half rho of pair interactions = +0.838 [+0.817, +0.858] over 533 turns
- 160000cu: split-half rho of pair interactions = +0.869 [+0.851, +0.885] over 582 turns
- 640000cu: split-half rho of pair interactions = +0.889 [+0.872, +0.904] over 342 turns

Full tables in `tables/`, figures in `figures/`.
