package com.lucid.auto.appstore

import android.app.Application

/**
 * Robolectric stand-in for [LucidAppStoreApplication].
 *
 * The real Application builds the whole dependency graph and wires the auto-update scheduler to
 * WorkManager on startup. Under Robolectric that would force every test to stand up WorkManager
 * before it could render a single Composable, so tests run against this empty Application and
 * construct their collaborators explicitly.
 */
class TestApplication : Application()
