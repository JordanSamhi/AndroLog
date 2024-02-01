<p align="center">
<img width="1200px" src="https://github.com/JordanSamhi/AndroLog/blob/main/data/androlog_logo.png">
</p> 

# AndroLog

Welcome to **AndroLog**, your simple solution to insert probes into Android apps with the goal to compute code coverage at runtime.

**AndroLog** offers, so far, several levels of granularity:
- Classes
- Methods
- Statements
- Activities
- Services
- Broadcast Receivers
- Content Providers

Do not hesitate to contribute or open issues may you need additional levels of granularity.

## :rocket: Getting started

### :arrow_down: Downloading the tool

<pre>
git clone https://github.com/JordanSamhi/AndroLog.git
</pre>

### :wrench: Installing the tool

<pre>
cd AndroLog
mvn clean install
</pre>

### :computer: Using the tool

🔧 Before using AndroLog, make sure to set the paths to zipalign and apksigner in the config.properties file located in `src/main/resources/`

Usage:

<pre>
java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar <i>options</i>
</pre>

Options:

* ```-a``` : The path to the APK to process.
* ```-p``` : The path to Android platofrms folder.
* ```-l``` : The log identifier to use.
* ```-o``` :  The output where to write the instrumented APK.
* ```-pa``` : Parsing runtime output logs.
* ```-c``` : Logging classes.
* ```-m``` : Logging methods.
* ```-s``` : Logging statements.
* ```-cp``` : Logging Android components (Activity, Service, BroadcastReceiver, ContentProvider).
* ```-n``` : If set, this flag tells AndroLog to not consider libraries in the process.

### :information_source: Examples

#### Instrumenting

<pre>
  java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar -p ./Androidplatforms/ -l MY_SUPER_LOG -o ./output/ -a my_app.apk -c -m -cp
</pre>

#### Computing Code Coverage

<pre>
  java -jar AndroLog/target/androlog-0.1-jar-with-dependencies.jar -p ./Androidplatforms/ -l MY_SUPER_LOG -a my_app.apk -c -m -cp -pa logs
</pre>

## :hammer: Built With

* [Maven](https://maven.apache.org/) - Dependency Management

## :page_with_curl: License

This project is licensed under the GNU LESSER GENERAL PUBLIC LICENSE 2.1 - see the [LICENSE](LICENSE) file for details

## :email: Contact

For any question regarding this study, please contact us at:
[Jordan Samhi](mailto:j.samhi@me.com)
