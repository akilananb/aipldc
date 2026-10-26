import { Config } from '@remotion/cli/config';

Config.setVideoImageFormat('jpeg');
Config.setCodec('h264');
Config.setMuted(true);
// Use the installed Chrome; the downloaded headless shell crashed on this Mac.
Config.setBrowserExecutable('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome');
