package com.humblesolutions.ainotetaker.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.humblesolutions.ainotetaker.i18n.Language
import com.humblesolutions.ainotetaker.model.Appearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * ONE DataStore for the whole app.
 *
 * Two `preferencesDataStore` delegates naming the same file throw at runtime,
 * not at compile time — so the delegate lives here and everything else reaches
 * it through this property.
 */
internal val Context.tasPrefs by preferencesDataStore(name = "tas_prefs")

/**
 * Only still named so the old value can be DELETED.
 *
 * The notetaker used to ask each person to paste their own Anthropic key in and
 * kept it here. Nothing reads it any more — but an upgrade does not empty a
 * DataStore entry, so without [forgetLegacyClaudeKey] a live `sk-ant-…` would
 * sit in the preferences of every handset that ran an older build, which is the
 * exact leak that moving the key to Secret Manager was meant to close.
 *
 * SAFE TO DELETE, along with the purge, once no device can still be running a
 * build from before the move.
 */
private val CLAUDE_KEY = stringPreferencesKey("claudeApiKey")
private val APPEARANCE = stringPreferencesKey("appearance")

/**
 * Whether the source-and-disclaimer sheet has been acknowledged on this device.
 *
 * PER INSTALL, NOT PER SIGN-IN. It is a statement about the app, not about the
 * person — so it is shown once, and after that it lives behind the (i) in the
 * top bar. A fresh install shows it again, which is exactly what a Play
 * reviewer needs to see.
 */
private val DISCLAIMER_ACK = booleanPreferencesKey("disclaimerAcknowledged")

/**
 * The app's preferences on this device.
 *
 * the app is English only, so [language] is always English and
 * nothing can set another. It stays a flow because the recorder's
 * notifications (kept byte-identical to TAS) read it from here; a language a
 * device chose under an older build is no longer read.
 */
class LanguageStore(private val context: Context) {

    val language: Flow<Language> = flowOf(Language.ENGLISH)


    /** Removes the Anthropic key older builds stored on this device. Idempotent. */
    suspend fun forgetLegacyClaudeKey() {
        context.tasPrefs.edit { it.remove(CLAUDE_KEY) }
    }

    /**
     * False until the person has seen where the electoral roll comes from and
     * that this is not a government app. Defaults to false, so a device that
     * has never answered shows the sheet rather than silently skipping it.
     */
    val disclaimerAcknowledged: Flow<Boolean> =
        context.tasPrefs.data.map { it[DISCLAIMER_ACK] ?: false }

    suspend fun setDisclaimerAcknowledged() {
        context.tasPrefs.edit { it[DISCLAIMER_ACK] = true }
    }

    /** Light, dark, or the phone's own setting. Defaults to the phone's. */
    val appearance: Flow<Appearance> =
        context.tasPrefs.data.map { Appearance.fromCode(it[APPEARANCE]) }

    suspend fun setAppearance(appearance: Appearance) {
        context.tasPrefs.edit { it[APPEARANCE] = appearance.code }
    }
}
