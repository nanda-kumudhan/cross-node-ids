# Offline feature preparation

Use Python 3.12 and `pip install -r ml/requirements.txt` in a virtual environment.
Supply the separately acquired official ADFA-LD trace directories:

```sh
python ml/preprocess.py --train /data/Training_Data_Master \
  --validation /data/Validation_Data_Master --attack /data/Attack_Data_Master \
  --output ml/output --n 3
python -m unittest discover -s ml/tests
```

Outputs: sparse `.npz` matrices, per-trace novelty scores, training vocabulary,
and a manifest of source paths and canonical trace hashes. No pickled objects.
Use the included Colab notebook to prepare data after uploading your own files.
Model training and evaluation remain the experiment described in MODEL_CARD.md.
