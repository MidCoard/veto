import unittest
from collections import Counter
from pathlib import Path

from evaluate_screening_hf import (
    classification_metrics,
    relative_report_path,
    underestimation_rate,
)


class ScreeningMetricsTest(unittest.TestCase):
    def test_artifact_paths_are_relative_to_the_report_file(self):
        value = relative_report_path(Path("models/model"), Path("reports/eval.json"))

        self.assertEqual("../models/model", value)
        self.assertNotIn(":", value)

    def test_reports_per_class_and_macro_scores(self):
        confusion = Counter(
            {
                ("SAFE", "SAFE"): 2,
                ("SAFE", "ELEVATED"): 1,
                ("ELEVATED", "ELEVATED"): 1,
            }
        )

        metrics = classification_metrics(confusion, ("SAFE", "ELEVATED"))

        self.assertAlmostEqual(2 / 3, metrics["perClass"]["SAFE"]["recall"])
        self.assertAlmostEqual(1 / 2, metrics["perClass"]["ELEVATED"]["precision"])
        self.assertGreater(metrics["macroF1"], 0)

    def test_counts_only_predictions_less_severe_than_expected(self):
        confusion = Counter(
            {
                ("CRITICAL", "DANGEROUS"): 2,
                ("DANGEROUS", "CRITICAL"): 3,
                ("SAFE", "SAFE"): 5,
            }
        )

        self.assertAlmostEqual(
            0.2,
            underestimation_rate(
                confusion, ("SAFE", "ELEVATED", "DANGEROUS", "CRITICAL")
            ),
        )


if __name__ == "__main__":
    unittest.main()
