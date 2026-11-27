import { expect, test } from 'vitest';
import { number } from './format';
import { terminal } from './types';

test('missing observations are never rendered as zero', () => {
  expect(number(null)).toBe('—');
  expect(number(NaN)).toBe('—');
  expect(number(0, 'ms')).toBe('0 ms');
});
test('byte counts are labelled as binary units', () => expect(number(1048576, 'bytes')).toBe('1.00 MiB'));
test('interrupted trials cannot be treated as running', () => {
  expect(terminal('incomplete')).toBe(true);
  expect(terminal('saving_video')).toBe(false);
});
