package com.dnfapps.arrmatey.datastore

import com.dnfapps.arrmatey.model.AppColor
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

actual class DataStoreFactory {
    actual val defaultAppColor: AppColor = AppColor.ArrMatey

    actual fun provideDataStore() = createDataStore { documentPath(DATA_STORE_FILE_NAME) }

    actual fun provideServiceRateLimitDataStore() = createDataStore { documentPath(SERVICE_RATE_LIMIT_FILE_NAME) }

    actual fun provideInstanceDataStore(instanceId: Long) = createDataStore { documentPath(instanceDataStoreFileName(instanceId)) }

    actual fun providePlatformDataStore() = createDataStore { documentPath("ios_$DATA_STORE_FILE_NAME") }

    private fun documentPath(file: String): String {
        val directory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null,
        )
        return requireNotNull(directory).path + "/$file"
    }
}
