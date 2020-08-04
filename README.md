# AndroidAPS

*  Check the wiki: http://wiki.androidaps.org
*  Everyone who’s been looping with AndroidAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

This version tailored to my wife's needs & preferences.
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

[![Gitter](https://badges.gitter.im/MilosKozak/AndroidAPS.svg)](https://gitter.im/MilosKozak/AndroidAPS?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build status](https://travis-ci.org/MilosKozak/AndroidAPS.svg?branch=master)](https://travis-ci.org/MilosKozak/AndroidAPS)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.androidaps.org/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://androidaps.readthedocs.io/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/MilosKozak/AndroidAPS/branch/master/graph/badge.svg)](https://codecov.io/gh/MilosKozak/AndroidAPS)
dev: [![codecov](https://codecov.io/gh/MilosKozak/AndroidAPS/branch/dev/graph/badge.svg)](https://codecov.io/gh/MilosKozak/AndroidAPS)


[![Donate via PayPal](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=Y4LHGJJESAVB8)
