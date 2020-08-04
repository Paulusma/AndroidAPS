# AndroidAPS

This version is tailored to my wife's needs, configuration (libre sensors & akkuchek Insight pump) & preferences.
Provided for informational purpose only. DO NOT USE to build your own, use the latest stable release of AndroidAPS instead!

Based on 2.3 with additions (core implementation as plugins in package info.nightscout.androidaps.plugins.hm):

- low BG prevention and hypo predictor plugin
- plugin that drops BG target on stable BG
- wizard using predefined meals
- auto pre-bolus/eating soon after selecting meal & message when to start eating
- fast history browser plugin stateviewer
- warning when insulin reservoir low
- warning when pump battery low
- warning when libre sensor is past 13 days (depends on xDrip mod)
- NS upload of BG reading so xDrip can be local (depends on xDrip mod)
- black background on overview
- display insuline left on overview and ignore 25U left on insight pump warning
- warning when phone battery < 20% and not charging
- some minor changes
