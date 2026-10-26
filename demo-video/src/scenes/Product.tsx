import React from 'react';
import { Scene } from '../components';
import { Rect, Screen } from '../Screen';
import { Subtitle } from '../Subtitle';

// Walkthrough scenes on real eLoop.ai screens (captured by scripts/capture.mjs).
// Rects are CSS px in the 1600×1000 capture; positions come from public/screens/*.json.

const r = (x: number, y: number, w: number, h: number): Rect => ({ x, y, w, h });
const FULL = r(0, 0, 1600, 1000);

type P = { duration: number };

/** The one-line card on the real board. */
export const BoardHook: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src="screens/f-list.png"
      cam={[
        { at: 0, view: FULL },
        { at: 30, view: r(180, 150, 1020, 440) },
      ]}
      highlights={[{ at: 52, rect: r(212, 386, 960, 104) }]}
    />
    <Subtitle icon="file" label="The ask" duration={duration} text="Every feature starts as **one line** on the board." />
  </Scene>
);

export const RealClarify: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src="screens/f-clarify.png"
      cam={[
        { at: 0, view: FULL },
        { at: 26, view: r(160, 190, 1180, 560) },
        { at: 140, view: r(160, 160, 1420, 680) },
      ]}
      highlights={[
        { at: 50, until: 140, rect: r(340, 540, 1150, 40) },
        { at: 80, until: 140, rect: r(340, 753, 1150, 40) },
        { at: 150, rect: r(1238, 525, 330, 320) },
      ]}
    />
    <Subtitle
      icon="help"
      label="01 · Clarify"
      stage={0}
      duration={duration}
      text="The **grill agent** asks the questions people forget, before anyone writes code."
    />
  </Scene>
);

export const RealStoryGate1: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src={[
        { at: 0, src: 'screens/f-quality.png' },
        { at: 92, src: 'screens/f-g1.png' },
      ]}
      cam={[
        { at: 0, view: r(170, 290, 1400, 520) },
        { at: 92, view: r(160, 60, 1300, 330) },
      ]}
      highlights={[
        { at: 18, until: 86, rect: r(206, 466, 560, 128) },
        { at: 46, until: 86, rect: r(300, 757, 1270, 38) },
        { at: 112, rect: r(268, 250, 900, 76) },
        { at: 142, rect: r(268, 324, 420, 36) },
      ]}
    />
    <Subtitle
      icon="shield"
      label="02 · Gate 1"
      stage={2}
      duration={duration}
      text="Quality agent **blocks a weak draft.** PO and Squad Lead **sign v2.**"
    />
  </Scene>
);

export const RealPlanBuild: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src={[
        { at: 0, src: 'screens/f-tasks0.png' },
        { at: 92, src: 'screens/f-tasks5.png' },
      ]}
      cam={[
        { at: 0, view: r(180, 40, 1400, 820) },
        { at: 100, view: r(180, 330, 1400, 520) },
      ]}
      highlights={[
        { at: 30, until: 88, rect: r(205, 755, 1365, 48) },
        { at: 118, rect: r(205, 756, 1365, 48) },
      ]}
    />
    <Subtitle
      icon="layers"
      label="03 · Plan + build"
      stage={3}
      duration={duration}
      text="Plan agent splits the work. Build agent turns **5 of 5 tasks green.**"
    />
  </Scene>
);

export const RealReviewGate2: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src={[
        { at: 0, src: 'screens/f-pr.png' },
        { at: 84, src: 'screens/f-g2.png' },
      ]}
      cam={[
        { at: 0, view: r(170, 150, 1420, 420) },
        { at: 84, view: r(150, 150, 1180, 300) },
      ]}
      highlights={[
        { at: 24, until: 80, rect: r(300, 366, 1275, 40) },
        { at: 104, rect: r(310, 302, 196, 52) },
      ]}
    />
    <Subtitle
      icon="pr"
      label="04 · Gate 2"
      stage={5}
      duration={duration}
      text="Review agent **catches a hard-coded threshold.** The developer approves the PR."
    />
  </Scene>
);

export const RealReleaseGate3: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src="screens/f-release.png"
      cam={[
        { at: 0, view: r(180, 100, 1400, 820) },
        { at: 100, view: r(912, 470, 660, 312) },
      ]}
      highlights={[
        { at: 22, until: 100, rect: r(282, 150, 622, 296) },
        { at: 52, until: 100, rect: r(282, 445, 622, 300) },
        { at: 112, rect: r(928, 530, 622, 230) },
      ]}
    />
    <Subtitle
      icon="file"
      label="05 · Gate 3"
      stage={7}
      duration={duration}
      text="Release agent **drafts every document.** Each one is **signed by a named person.**"
    />
  </Scene>
);

export const RealMonitor: React.FC<P> = ({ duration }) => (
  <Scene duration={duration}>
    <Screen
      src="screens/f-bug.png"
      cam={[
        { at: 0, view: r(180, 50, 1400, 600) },
        { at: 30, view: r(220, 90, 1360, 300) },
      ]}
      highlights={[
        { at: 40, rect: r(266, 274, 1300, 84) },
      ]}
    />
    <Subtitle
      icon="activity"
      label="06 · Monitor"
      stage={8}
      duration={duration}
      text="Monitor trips at **18%** and files the evidence **straight back to the board.**"
    />
  </Scene>
);
