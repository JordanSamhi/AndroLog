import argparse
import logging
import os

import source_instrumenter
from apk_instrumenter import ApkInstrumenter

logging.basicConfig(
    format="%(asctime)s> [%(levelname)s][%(name)s][%(funcName)s()] %(message)s",
    datefmt="%d/%m/%Y %H:%M:%S",
    level=logging.DEBUG,
)


def get_cmd_args(args: list = None):
    """
    Parse and return the command line parameters needed for the script execution.

    :param args: List of arguments to be parsed (by default sys.argv is used).
    :return: The command line needed parameters.
    """

    parser = argparse.ArgumentParser(
        prog='python3 cli.py',
        description='Automatically instrument an Android application with JaCoCo.',
    )
    parser.add_argument(
        'app',
        type=str,
        metavar='<APP>',
        help='The path to the apk file or the directory containing the source code '
             'of the application to be instrumented',
    )
    return parser.parse_args(args)


def main():
    arguments = get_cmd_args()

    if arguments.app:
        arguments.app = arguments.app.strip(' "\'')

    if os.path.isdir(arguments.app):
        # This is a directory, so the source code of the application is expected.
        source_instrumenter.run_instrumentation(arguments.app)
    elif os.path.isfile(arguments.app):
        # This is a file, so a valid Android application is expected.
        ApkInstrumenter(arguments.app).run_instrumentation()
    else:
        raise FileNotFoundError('Directory or file "{0}" does not exist, unable to '
                                'proceed with the instrumentation'.format(arguments.app))


if __name__ == '__main__':
    main()
