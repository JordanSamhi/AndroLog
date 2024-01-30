# COSMO

[![Ubuntu Build Status](https://github.com/H2SO4T/COSMO/workflows/Ubuntu/badge.svg)](https://github.com/H2SO4T/COSMO/actions?query=workflow%3AUbuntu)
[![Windows Build Status](https://github.com/H2SO4T/COSMO/workflows/Windows/badge.svg)](https://github.com/H2SO4T/COSMO/actions?query=workflow%3AWindows)
[![MacOS Build Status](https://github.com/H2SO4T/COSMO/workflows/MacOS/badge.svg)](https://github.com/H2SO4T/COSMO/actions?query=workflow%3AMacOS)
[![Python Version](https://img.shields.io/badge/Python-3.5%2B-green.svg?logo=python&logoColor=white)](https://www.python.org/downloads/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](https://github.com/H2SO4T/COSMO/blob/master/LICENSE)



COSMO is a tool that allows you to automatically instrument a gradle-based application and to generate code coverage reports.
Code coverage is a measure useful to describe the degree to which the source code of a program is executed when a particular test suite runs.
COSMO can automatically modify the source code of the Android app. In addition, COSMO can operate as a black-box tool, in-strumenting an app starting from the compiled app.

COSMO from source code demonstrated to work with more than 700 apps over 800:
[Experimental Results](https://github.com/H2SO4T/COSMO/actions?query=workflow%3A%22Instrumentation+%28from+source%29%22)

COSMO from apk code demonstrated to work with 300 apps over 400:
[Experimental Results](https://github.com/H2SO4T/COSMO/actions?query=workflow%3A%22Instrumentation+%28from+apk%29%22)


<p align='center'><img src='./images/cosmo.jpg'></p>

## Works with

- ```Java projects```
- ```Kotlin projects```

# Requirements

| Python version     | Ubuntu                   | Windows                  | MacOS                    |
|:------------------:|:------------------------:|:------------------------:|:------------------------:|
| **3.4** or lower   | :heavy_multiplication_x: | :heavy_multiplication_x: | :heavy_multiplication_x: |
| **3.5**            | :heavy_check_mark:       | :heavy_check_mark:       | :heavy_check_mark:       |
| **3.6**            | :heavy_check_mark:       | :heavy_check_mark:       | :heavy_check_mark:       |
| **3.7**            | :heavy_check_mark:       | :heavy_check_mark:       | :heavy_check_mark:       |
| **3.8**            | :heavy_check_mark:       | :heavy_check_mark:       | :heavy_check_mark:       |
| **3.9** or greater | :heavy_multiplication_x: | :heavy_multiplication_x: | :heavy_multiplication_x: |

# Installation

The first thing to do is to get a local copy of this repository, so open up a terminal in the directory where you want
to save the project and clone the repository:

``` https://github.com/H2SO4T/COSMO.git```

Now create a virtualenv, and run ```pip3 install -r requirements.txt```. Now you are able to run your instrumentation.

# COSMO from Source: Usage

## CLI 

In order to run COSMO using the CLI, launch the command ```python3 cli.py <APP_PATH>```.
COSMO will eventually notify to you possible problems. 

# What Happens to your project?

Jacoco calls the following methods in order to instrument your application: 

- ```instrument_android_manifest()```: it searches for the package name in ```AndroidManifest.xml``` while in the mean time
adds a ```receiver``` node. At the end overwrites the original ```AndroidManifest.xml```, keeping a copy of the old one
called ```AndroidManifest.xml.old```.
 
- ```create_instrumentation_classes()```: it generates ```EndCoverageBroadcast.java``` file in the main directory of the project.

- ```modify_gradle()```: it has the duty of adding the missing dependencies to ```build.gradle``` file, the original one will be
called ```build.gradle.old```. In this process we add the lines `debug { testCoverageEnabled = true }`.

- ```jacoco_properties()``` generates a file called  ```jacoco-agent.properties``` in ```resources``` folder.

When the app is built with `testCoverageEnabled = true`, it contains the code of Jacoco
needed for the coverage (take a look at the decompiled source code, it will be full of
variables named `$jacoco...`). At this point we only have to run the app and to collect
the coverage results when we are done with the testing.

We need a way to tell the app when the testing phase is over and the results should be
generated. For this purpose, we can use a
[broadcast receiver](https://github.com/H2SO4T/COSMO/blob/master/templates/EndCoverageBroadcast.prep)
that will create the file with the coverage data when receiving `intent.END_COVERAGE`
broadcast.

For the broadcast receiver to work, we have to register it in the application. We do so
by inserting a `receiver` node in the manifest of the instrumented application, that
will listen for `intent.END_COVERAGE` broadcasts and will trigger the report generation.

# COSMO from APK: Usage

In order to run COSMO from apk, launch the command ```python3 cli.py <APK>```.
It works only with debug apks. 

# How to run Code Coverage

The only thing that you have to do is to launch you application with any tool you want (by hand, using Appium etc.).
At the end of your test you send the command ``` adb shell am broadcast -p <package.name> -a intent.END_COVERAGE ```.
`-p <package.name>` is needed only when running the application on a device with at least Android 8.0 (API level 26),
due to the new limitations introduced for implicit broadcasts.


# Generating a HTML/CSV report

To generate the final coverage report, you need to do the following:

- get the `coverage.ec` file from the device/emulator and save it into a directory on
your computer (e.g., on the desktop). This file is generated after you send the
broadcast to end the coverage and is located in
`/sdcard/Android/data/<package.name>/files/` directory;

- modify `jacoco-instrumenter-coverage.gradle` file by replacing `/path/to/coverage/dir`
with the path of the directory where you saved `coverage.ec` file.
`jacoco-instrumenter-coverage.gradle` is added during the instrumentation and is located
in the same directory as the `build.gradle` of the instrumented application;

- from the main directory of the instrumented application, run the task
`jacocoInstrumenterReport` (e.g., `./gradlew jacocoInstrumenterReport`). If everything
proceeds without errors, the HTML and CSV coverage reports will be generated in the
`build/reports/jacoco/jacocoInstrumenterReport/` directory of the application (the same
directory containing `jacoco-instrumenter-coverage.gradle` file).

# Use Case

## Android Code Coverage using Appium

In case you are using [Appium](https://github.com/appium/appium), you can obtain the code coverage from your tests with very few changes.
First you need to set the following capabilities:
```python
desired_caps = {'platformName': '',
                'platformVersion': '',
                'udid': '',
                'deviceName': '',
                'app': app_name,
                'autoGrantPermissions': True,
                'fullReset': False,
                'unicodeKeyboard': True,
                'resetKeyboard': True,
                'isHeadless': False,
                'automationName': 'uiautomator2'
                'appWaitActivity': main_activity}
```
```appWaitActivity``` must contain the name of the main activity (only in the case it is not a SplashActivity).
Otherwise, you should place a string in your application that includes all the activities.
Now you can instantiate your driver by doing:

```python
driver = webdriver.Remote('http://localhost:4723/wd/hub', desired_caps)
```

At last, at the end of your test you can do:

```python
# this ends the code coverage and lets the app generate coverage.ec file
os.system('adb shell am broadcast -p com.example.package -a intent.END_COVERAGE')
# just wait a little bit
time.sleep(0.5)
# pull the coverage.ec file from the device to your computer
os.system('adb -P 5037 -s emulator-5554 pull <PATH_TO_FILE>/coverage.ec <DESTINATION_PATH>')
# you can can also quit the driver
self.driver.reset()
```
