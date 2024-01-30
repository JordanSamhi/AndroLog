import glob
import logging
import os
import re
import shutil
import xml.etree.ElementTree as ET

import preppy as preppy

logger = logging.getLogger(__name__)


SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))


ANDROID_NS = '{http://schemas.android.com/apk/res/android}'


def generate_receiver_node():
    receiver = ET.Element('receiver')
    receiver.text = '\n            '
    receiver.tail = '\n        '
    receiver.set(ANDROID_NS + 'name', '.EndCoverageBroadcast')

    intent_filter = ET.Element('intent-filter')
    intent_filter.text = '\n                '
    intent_filter.tail = '\n        '

    action = ET.Element('action')
    action.tail = '\n            '
    action.set(ANDROID_NS + 'name', 'intent.END_COVERAGE')

    intent_filter.append(action)
    receiver.append(intent_filter)

    return receiver


def instrument_android_manifest(manifest_path: str):
    logger.info('Instrumenting Android manifest "{0}"'.format(manifest_path))

    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')

    # If available, use the backup copy of the file (this is useful when instrumenting
    # multiple times the same application).
    if os.path.isfile(manifest_path + '.old'):
        tree = ET.parse(manifest_path + '.old')
    else:
        tree = ET.parse(manifest_path)
        # The backup copy of the file is not present, so create it.
        tree.write(manifest_path + '.old', encoding='utf-8', xml_declaration=True)

    root = tree.getroot()

    # Saving package for instrumentation
    package = root.attrib['package']

    for child in root:
        if child.tag == 'application':
            child.insert(0, generate_receiver_node())
    tree.write(manifest_path, encoding='utf-8', xml_declaration=True)

    return package


def jacoco_properties(manifest_path: str):
    logger.info('Adding "jacoco-agent.properties" file')

    # "jacoco-agent.properties" file has to be put in a "resources" directory in the
    # same directory as the Android manifest file.
    resources_dir = os.path.join(os.path.dirname(manifest_path), 'resources')
    os.makedirs(resources_dir, exist_ok=True)
    shutil.copyfile(os.path.join(SCRIPT_DIR, 'templates', 'jacoco-agent.properties'),
                    os.path.join(resources_dir, 'jacoco-agent.properties'))


def create_instrumentation_classes(manifest_path, package):
    logger.info('Instrumenting Java classes')

    # Get the source code directory starting from the manifest path. The source code
    # could be in a different location, here we try the most common paths.
    source_code_dir_candidates = [
        os.path.join(os.path.dirname(manifest_path), 'java', *package.split('.')),
        os.path.join(os.path.dirname(manifest_path), 'src', *package.split('.')),
        os.path.join(os.path.dirname(manifest_path), 'src', 'main', *package.split('.')),
        # TODO: Kotlin support
        # os.path.join(os.path.dirname(manifest_path), 'kotlin', *package.split('.'))
    ]

    source_code_dir = None
    for directory in source_code_dir_candidates:
        if os.path.isdir(directory):
            source_code_dir = directory
            break

    if not source_code_dir:
        raise NotADirectoryError(
            'Impossible to find a source code directory among {0}'.format(
                source_code_dir_candidates
            )
        )

    # Generating EndCoverageBroadcast
    template = preppy.getModule(os.path.join(SCRIPT_DIR, 'templates', 'EndCoverageBroadcast.prep'))
    my_java = template.get(package)
    with open(os.path.join(source_code_dir, 'EndCoverageBroadcast.java'), 'w') as my_file:
        my_file.write(my_java)


def modify_gradle(app_gradle_file_path: str, folder: str):
    logger.info('Instrumenting Gradle file')

    # If available, use the backup copy of the file (this is useful when instrumenting
    # multiple times the same application).
    if os.path.isfile(app_gradle_file_path + '.old'):
        # Restore the backup copy before proceeding.
        shutil.copyfile(app_gradle_file_path + '.old', app_gradle_file_path)
    else:
        # The backup copy of the file is not present, so create it.
        shutil.copyfile(app_gradle_file_path, app_gradle_file_path + '.old')

    with open(app_gradle_file_path, 'r', encoding='utf-8') as gradle_file:
        a_gradle_text = gradle_file.readlines()

    debug = False

    i = 0
    while i < len(a_gradle_text):
        if not debug:
            if a_gradle_text[i].find('buildTypes {') > -1:
                a_gradle_text.insert(i + 1, '        debug {\n')
                a_gradle_text.insert(i + 2, '            testCoverageEnabled = true\n')
                a_gradle_text.insert(i + 3, '        }\n')
                debug = True
                break
        i += 1

    if not debug:
        # buildTypes block was not found, let's add it.
        i = 0
        while i < len(a_gradle_text):
            if not debug:
                if a_gradle_text[i].find('android {') > -1:
                    a_gradle_text.insert(i + 1, '    buildTypes {\n')
                    a_gradle_text.insert(i + 2, '        debug {\n')
                    a_gradle_text.insert(i + 3,'            testCoverageEnabled = true\n')
                    a_gradle_text.insert(i + 4, '        }\n')
                    a_gradle_text.insert(i + 5, '    }\n')
                    debug = True
                    break
            i += 1

    if not debug:
        raise RuntimeError('Impossible to insert "testCoverageEnabled = true" in '
                           'file "{0}"'.format(app_gradle_file_path))

    # The task with the coverage is in a separate file.
    a_gradle_text.append("\napply from: 'jacoco-instrumenter-coverage.gradle'\n")

    with open(app_gradle_file_path, 'w', encoding='utf-8') as gradle_file:
        gradle_file.writelines(a_gradle_text)

    # Add "jacoco-coverage.gradle" file in the same directory as the Gradle file
    # (it contains the task to generate the coverage report).
    version = 6

    gradle_wrapper_path = glob.glob(os.path.join(folder, '**', 'gradle-wrapper.properties'), recursive=True)
    if len(gradle_wrapper_path) > 0:
        with open(gradle_wrapper_path[0], 'r', encoding='utf-8') as gradle_wrapper:
            for line in gradle_wrapper.readlines():
                if line.find('distributionUrl') >= 0:
                    version = int(re.search('\d+', line).group())
    if version >= 6:
        coverage_gradle = 'jacoco-instrumenter-coverage.gradle'
    else:
        coverage_gradle = 'jacoco-instrumenter-coverage-old-gradle.gradle'
    shutil.copyfile(os.path.join(SCRIPT_DIR, 'templates',
                                 coverage_gradle),
                    os.path.join(os.path.dirname(app_gradle_file_path),
                                 'jacoco-instrumenter-coverage.gradle'))


def get_main_activities(manifest_path: str):
    """
    Get the main activity name(s) from an Android manifest file.

    Adapted from Androguard:
    https://github.com/androguard/androguard/blob/master/androguard/core/bytecodes/apk.py

    :param manifest_path: The path to the Android manifest file.
    :return: A set containing the main activity name(s).
    """
    x = set()
    y = set()

    tree = ET.parse(manifest_path)
    activities_and_aliases = tree.findall('.//activity') + \
                             tree.findall('.//activity-alias')

    for item in activities_and_aliases:
        # Some applications have more than one MAIN activity.
        # For example: paid and free content.
        activity_enabled = item.get(ANDROID_NS + 'enabled')
        if activity_enabled == 'false':
            continue

        for action in item.findall('.//action'):
            val = action.get(ANDROID_NS + 'name')
            if val == 'android.intent.action.MAIN':
                activity = item.get(ANDROID_NS + 'name')
                if activity is not None:
                    x.add(item.get(ANDROID_NS + 'name'))
                else:
                    logger.warning('Main activity without name in "{0}"'.format(
                        manifest_path))

        for category in item.findall('.//category'):
            val = category.get(ANDROID_NS + 'name')
            if val == 'android.intent.category.LAUNCHER':
                activity = item.get(ANDROID_NS + 'name')
                if activity is not None:
                    y.add(item.get(ANDROID_NS + 'name'))
                else:
                    logger.warning('Launcher activity without name in "{0}"'.format(
                        manifest_path))

    return x.intersection(y)


def parse_android_project(project_dir: str):
    """
    Check if a directory contains a valid Android project.

    :param project_dir: The path to the Android project directory.
    :return: A tuple with the path to the "AndroidManifest.xml" file containing the
             main activity and the path to the corresponding "build.gradle" file.
    """
    logger.info('Parsing Android project directory "{0}"'.format(project_dir))

    if not os.path.isdir(project_dir):
        raise NotADirectoryError(
            'Invalid project directory "{0}"'.format(project_dir)
        )

    manifest_files = []
    gradle_files = []

    # Find all "AndroidManifest.xml" and "build.gradle" files.
    for root, dir_names, file_names in os.walk(project_dir, topdown=True):
        # Exclude hidden and build directories.
        dir_names[:] = [d for d in dir_names if d[0] != '.' and d != 'build']
        for file_name in file_names:
            if file_name == 'AndroidManifest.xml':
                manifest_files.append(os.path.join(root, file_name))
            elif file_name == 'build.gradle' or file_name == 'build.gradle.kts':
                gradle_files.append(os.path.join(root, file_name))

    if len(manifest_files) == 0:
        raise FileNotFoundError(
            'No "AndroidManifest.xml" file(s) found in project directory "{0}"'.format(
                project_dir
            )
        )

    if len(gradle_files) == 0:
        raise FileNotFoundError(
            'No "build.gradle" file(s) found in project directory "{0}"'.format(
                project_dir
            )
        )

    logger.debug('{0} "AndroidManifest.xml" candidate(s) found: {1}'.format(
        len(manifest_files), manifest_files
    ))
    logger.debug('{0} "build.gradle" candidate(s) found: {1}'.format(
        len(gradle_files), gradle_files
    ))

    # Find the Android manifest file containing the main activity. Use a list in order
    # to check if there is more than one manifest file containing a main activity.
    main_manifests = []
    for manifest_file in manifest_files:
        main_activities = get_main_activities(manifest_file)
        if len(main_activities) > 0:
            main_manifests.append(manifest_file)

    if len(main_manifests) == 0:
        raise RuntimeError('None of the Android manifest files contains a main '
                           'activity to instrument')

    if len(main_manifests) > 1:
        raise RuntimeError('More Android manifest files contain a main activity ({0}), '
                           'this scenario is not supported yet'.format(main_manifests))

    logger.debug('"AndroidManifest.xml" file with main activity found: "{0}"'.format(
        main_manifests[0]
    ))

    # Find the "build.gradle" corresponding to the correct Android manifest (the one
    # in the closest parent directory - the one having the longest common path).
    correct_gradle_file = None
    longest_match = 0
    for gradle_file in gradle_files:
        for index in range(0, 1000):
            # Find the "build.gradle" file that has the longest path in common with
            # the correct "AndroidManifest.xml" file. Also, "build.gradle" file has
            # to be in a parent directory of "AndroidManifest.xml".
            if gradle_file[index] != main_manifests[0][index]:
                if index > longest_match and \
                        os.path.dirname(gradle_file) in main_manifests[0]:
                    longest_match = index - 1
                    correct_gradle_file = gradle_file
                break

    if correct_gradle_file is not None:
        if os.path.basename(correct_gradle_file) == 'build.gradle.kts':
            raise RuntimeError('"build.gradle" corresponding to the Android manifest '
                               'with the main activity found: "{0}", however, Kotlin '
                               'DSL is not supported yet'.format(correct_gradle_file))
        logger.debug('"build.gradle" corresponding to the Android manifest with the '
                     'main activity found: "{0}"'.format(correct_gradle_file))
    else:
        raise RuntimeError('Unable to find "build.gradle" file corresponding to '
                           'Android manifest file "{0}"'.format(main_manifests[0]))

    with open(correct_gradle_file, 'r', encoding='utf-8') as gradle_file:
        file_content = gradle_file.read()
        android_plugin_old = re.search(r'apply\s+?plugin\s*?:\s+?'
                                       r'["\'](com\.android\.application|android)["\']',
                                       file_content)
        android_plugin_new = re.search(r'plugins\s*?{[^}]*?id[\s(]+'
                                       r'["\'](com\.android\.application|android)["\']',
                                       file_content)
        if not (android_plugin_old or android_plugin_new):
            logger.warning('Android plugin usage not found in "{0}", please make sure '
                           'this is the correct "build.gradle" file to instrument'
                           .format(correct_gradle_file))

    return main_manifests[0], correct_gradle_file


def run_instrumentation(folder: str):
    folder = os.path.normpath(folder)

    try:
        manifest_path, app_gradle_path = parse_android_project(folder)
        package = instrument_android_manifest(manifest_path)
        create_instrumentation_classes(manifest_path, package)
        jacoco_properties(manifest_path)
        modify_gradle(app_gradle_path, folder)
    except Exception as e:
        logger.critical('Error during instrumentation: {0}'.format(e), exc_info=True)
        raise
