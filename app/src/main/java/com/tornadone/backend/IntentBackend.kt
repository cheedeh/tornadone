package com.tornadone.backend

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.tornadone.data.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class IntentBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
) : TaskBackend {

    override val name: String = "Intent Broadcast"

    override suspend fun notifyTaskCreated(description: String): DispatchResult {
        val targetPackage = preferencesManager.shareTargetPackage
        Log.d(TAG, "notifyTaskCreated: target=$targetPackage desc=\"$description\"")

        val result = if (targetPackage.isNotEmpty()) {
            val openTasks = tryOpenTasks(targetPackage, description)
            if (openTasks) {
                DispatchResult(DispatchMethod.OPENTASKS, true, "Inserted via OpenTasks")
            } else {
                val tasker = tryTaskerBroadcast(targetPackage, description)
                if (tasker) {
                    DispatchResult(DispatchMethod.TASKER, true, "Sent via Tasker broadcast (no delivery confirmation)")
                } else {
                    val share = tryActivityIntents(targetPackage, description)
                    if (share) {
                        DispatchResult(DispatchMethod.SHARE, true, "Opened via ACTION_SEND (no delivery confirmation)")
                    } else {
                        Log.w(TAG, "All dispatch methods failed for $targetPackage")
                        DispatchResult(DispatchMethod.NONE, false, "All dispatch methods failed for $targetPackage")
                    }
                }
            }
        } else {
            DispatchResult(DispatchMethod.NONE, true, "No share target configured")
        }

        return result
    }

    /**
     * Try inserting via OpenTasks content provider (org.dmfs.provider.tasks).
     * Completely silent — direct DB insert, no UI.
     */
    private fun tryOpenTasks(packageName: String, title: String): Boolean {
        val authority = "$packageName.opentasks"
        val listsUri = Uri.parse("content://$authority/tasklists")

        // Check if this package has an OpenTasks provider
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        if (provider == null) {
            Log.d(TAG, "No OpenTasks provider at $authority")
            return false
        }
        Log.d(TAG, "Found OpenTasks provider: $authority")

        try {
            // Find the first task list to insert into
            val listCursor = context.contentResolver.query(
                listsUri, arrayOf("_id", "list_name"), null, null, null
            )
            val listId = listCursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    Log.d(TAG, "Using task list: $name (id=$id)")
                    id
                } else null
            }
            if (listId == null) {
                Log.w(TAG, "No task lists found in $authority")
                return false
            }

            // Insert the task
            val values = ContentValues().apply {
                put("list_id", listId)
                put("title", title)
                put("description", "Created by Tornadone")
                put("status", 0) // STATUS_NEEDS_ACTION
            }
            val tasksUri = Uri.parse("content://$authority/tasks")
            val result = context.contentResolver.insert(tasksUri, values)
            Log.d(TAG, "OpenTasks insert result: $result")
            return result != null
        } catch (e: SecurityException) {
            Log.w(TAG, "OpenTasks permission denied for $authority: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "OpenTasks insert failed for $authority: ${e.message}")
            return false
        }
    }

    /**
     * Try Tasker FIRE_SETTING broadcast (silent, no UI).
     * Works with tasks.org and other apps with Tasker plugin support.
     */
    private fun tryTaskerBroadcast(packageName: String, title: String): Boolean {
        val probe = Intent(TASKER_FIRE_SETTING).apply { setPackage(packageName) }
        val receivers = context.packageManager.queryBroadcastReceivers(probe, 0)
        Log.d(TAG, "Tasker FIRE_SETTING receivers for $packageName: ${receivers.map { it.activityInfo.name }}")
        if (receivers.isEmpty()) return false

        val receiver = receivers[0].activityInfo
        val bundle = Bundle().apply {
            putString("org.tasks.locale.create.STRING_TITLE", title)
            putInt("org.tasks.locale.create.INT_VERSION_CODE", 1)
        }
        val intent = Intent(TASKER_FIRE_SETTING).apply {
            setClassName(receiver.packageName, receiver.name)
            putExtra(TASKER_EXTRA_BUNDLE, bundle)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "Tasker broadcast sent to ${receiver.packageName}/${receiver.name}")
        return true
    }

    /**
     * Fall back to activity intents (ACTION_SEND). Opens target app's UI.
     */
    private fun tryActivityIntents(packageName: String, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, text)
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Log.d(TAG, "ACTION_SEND started for $packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_SEND failed for $packageName: ${e.message}")
            false
        }
    }

    fun dumpIntentFilters(packageName: String): List<String> {
        val pm = context.packageManager
        val actions = listOf(
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
            Intent.ACTION_VIEW,
            Intent.ACTION_INSERT,
            Intent.ACTION_EDIT,
            "com.google.android.gm.action.AUTO_SEND",
            "android.intent.action.PROCESS_TEXT",
        )
        val results = mutableListOf<String>()
        for (action in actions) {
            val probe = Intent(action).apply {
                type = "text/plain"
                setPackage(packageName)
            }
            val matches = pm.queryIntentActivities(probe, 0)
            if (matches.isNotEmpty()) {
                val activities = matches.joinToString { it.activityInfo.name }
                results.add("$action -> $activities")
            }
        }
        // Check broadcast receivers
        for (action in listOf(TASKER_FIRE_SETTING)) {
            val probe = Intent(action).apply { setPackage(packageName) }
            val matches = pm.queryBroadcastReceivers(probe, 0)
            if (matches.isNotEmpty()) {
                val receivers = matches.joinToString { it.activityInfo.name }
                results.add("$action -> $receivers (broadcast)")
            }
        }
        // Check OpenTasks provider
        val authority = "$packageName.opentasks"
        if (pm.resolveContentProvider(authority, 0) != null) {
            results.add("OpenTasks provider -> content://$authority")
        }
        return results
    }

    companion object {
        private const val TAG = "IntentBackend"
        private const val TASKER_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
        private const val TASKER_EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    }
}
