package ani.dantotsu.connections.crashlytics

class CrashlyticsFactory {
    companion object {
        fun createCrashlytics(): CrashlyticsInterface {
            return try {
                Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                FirebaseCrashlytics()
            } catch (e: Exception) {
                CrashlyticsStub()
            }
        }
    }
}