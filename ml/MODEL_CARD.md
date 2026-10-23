# Model card — evaluation pending

Status: preprocessing only. No trained model, accuracy, PR curve or live scoring
is claimed. The preprocessing unit is an entire recorded syscall trace; segmented
windows are future work and must keep all windows of one trace in its split.

Plan: train Isolation Forest on the official ADFA-LD normal training split.
Compare anomaly scores with the n-gram novelty baseline from preprocessing.
Calibrate thresholds on normal validation data, then report attack detection and
false-alarm rates. An attack split used in threshold tuning cannot also serve as
an independent test. Label any such evaluation accordingly. Retain trace IDs.

Do not reshuffle or merge the official splits. The vectorizer fits training only.
Unseen n-grams disappear from the model vector but contribute to the novelty
baseline; document that limitation. Cross-split duplicate traces fail preparation.

Obtain the dataset separately from its owner and review its terms; do not commit
the dataset. No downloading, redistribution or commercial-use authorization is
provided here. Record the exact dataset archive and hash in your experiment log.

Domain shift: ADFA syscall IDs, OS, workload and collection method differ from
the live agent. The exec/connect/accept event stream is not a full syscall trace
and cannot be passed to this model as if it were one. Own-agent evaluation needs
a compatible, separately implemented syscall capture path and an explicit mapping.
Scores are advisory; never use the model to enforce actions.
