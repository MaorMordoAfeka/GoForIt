
## how to use python in android project

# python files location
In this dir we put our python files that we will use in our project such as plot.py.

# use in kotlin
val imageView = findViewById<ImageView>(R.id.chartImage)
lifecycleScope.launchWhenStarted {
    val pngBytes: ByteArray = withContext(Dispatchers.Default) {
        val py = Python.getInstance()
        val mod = py.getModule("plots")
        mod.callAttr("make_demo_plot_png").toJava(ByteArray::class.java)
    }
    val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
    imageView.setImageBitmap(bmp)
}




