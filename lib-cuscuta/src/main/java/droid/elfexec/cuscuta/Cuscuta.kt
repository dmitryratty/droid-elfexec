package droid.elfexec.cuscuta

object Cuscuta {
    init {
        System.loadLibrary("jnifoo")
    }

    val stringFromJni: String?
        external get
}