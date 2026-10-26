import React, { useEffect, useState } from 'react';
import { AbsoluteFill, Series, continueRender, delayRender } from 'remotion';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/inter/800.css';
import { C } from './theme';
import { TitleSlide } from './scenes/TitleSlide';
import { Bottleneck, Pains } from './scenes/Act1';
import { Answer } from './scenes/Act2';
import {
  BoardHook,
  RealClarify,
  RealMonitor,
  RealPlanBuild,
  RealReleaseGate3,
  RealReviewGate2,
  RealStoryGate1,
} from './scenes/Product';
import { EndCard, Outcomes } from './scenes/Act4';

// Scene order and length in frames (30 fps). Total must be 1800 = 60s.
export const SCENES: [React.FC<{ duration: number }>, number][] = [
  [TitleSlide, 120], //       0:00–0:04  Enterprise AI Day title slide
  [BoardHook, 120], //        0:04–0:08  real board: the one-line card
  [Pains, 165], //            0:08–0:13  delivery pain points
  [Bottleneck, 90], //        0:13–0:16  hook line
  [Answer, 105], //           0:16–0:19  AI-Powered Software Delivery + pipeline
  [RealClarify, 195], //      0:19–0:26  grill agent (real screen)
  [RealStoryGate1, 180], //   0:26–0:32  quality fail → v2 signed (real)
  [RealPlanBuild, 180], //    0:32–0:38  tasks 0/5 → 5/5 (real)
  [RealReviewGate2, 150], //  0:38–0:43  review finding + gate 2 (real)
  [RealReleaseGate3, 165], // 0:43–0:49  release pack signatures (real)
  [RealMonitor, 120], //      0:49–0:53  monitor trip card (real)
  [Outcomes, 120], //         0:53–0:57  outcomes
  [EndCard, 90], //           0:57–1:00  end card
];

export const TOTAL_FRAMES = SCENES.reduce((n, [, d]) => n + d, 0);

export const Video: React.FC = () => {
  // Hold rendering until Inter is loaded, so no frame uses a fallback font.
  const [handle] = useState(() => delayRender('Loading Inter'));
  useEffect(() => {
    Promise.all(
      [400, 500, 600, 700, 800].map((w) => document.fonts.load(`${w} 32px Inter`)),
    ).then(() => continueRender(handle));
  }, [handle]);

  return (
    <AbsoluteFill style={{ background: C.white }}>
      <Series>
        {SCENES.map(([S, d], i) => (
          <Series.Sequence key={i} durationInFrames={d}>
            <S duration={d} />
          </Series.Sequence>
        ))}
      </Series>
    </AbsoluteFill>
  );
};
