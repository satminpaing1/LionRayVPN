package com.lionray.vpn.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * Portrait-locked scanner activity. The stock zxing CaptureActivity follows
 * sensor rotation which makes the screen flip to landscape; this subclass is
 * declared as screenOrientation="portrait" in the manifest so scanning always
 * stays upright.
 */
class CapturePortrait : CaptureActivity()
