"""MAGAT's analysis layer: a run tree in, the measures out.

    load.py      a run tree -> a turns frame, provenance, the identical-output check
    stats.py     scenario-clustered bootstrap, Cohen's d, variance components
    measures.py  one function per measure
    report.py    tables, SUMMARY.md, report.json, figures (`magat analyze`)

Dependencies: numpy and pandas; matplotlib is optional (figures only).
"""
__version__ = "0.1.0"
