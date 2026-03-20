import io
import numpy as np
import pandas as pd

import matplotlib
matplotlib.use("Agg")  # no GUI on Android
import matplotlib.pyplot as plt
import seaborn as sns


def make_demo_plot_png() -> bytes:
    rng = np.random.default_rng(42)
    df = pd.DataFrame({
        "x": np.arange(1, 101),
        "y": rng.normal(0, 1, 100).cumsum()
    })

    sns.set_theme()

    fig, ax = plt.subplots(figsize=(7, 3))
    sns.lineplot(data=df, x="x", y="y", ax=ax)
    ax.set_title("NumPy + Pandas + Seaborn (Android via Chaquopy)")
    ax.set_xlabel("x")
    ax.set_ylabel("y")

    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=160, bbox_inches="tight")
    plt.close(fig)

    return buf.getvalue()