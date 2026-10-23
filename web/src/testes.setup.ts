import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { esquecerSessaoParaTestes } from './sessao/api';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  esquecerSessaoParaTestes();
});
