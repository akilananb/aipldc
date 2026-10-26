import React from 'react';
import { Composition } from 'remotion';
import { TOTAL_FRAMES, Video } from './Video';
import { FPS, HEIGHT, WIDTH } from './theme';

export const Root: React.FC = () => (
  <>
    <Composition
      id="AiPdlcDemo"
      component={Video}
      durationInFrames={TOTAL_FRAMES}
      fps={FPS}
      width={WIDTH}
      height={HEIGHT}
    />
  </>
);
