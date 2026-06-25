package com.chthonic.dungeoncrawler.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { DungeonCrawlerApp() }
