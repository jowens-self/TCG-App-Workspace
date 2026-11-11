import { defineConfig } from 'vitest/config';

/**
 * Vitest over the Angular test builder on purpose.
 *
 * What is worth testing here is pure: two rules that decide what the UI says,
 * both extracted into their own modules precisely so they can be shared and
 * checked. Neither needs a browser, a TestBed, or a rendered component, and
 * standing up that machinery to exercise two functions would cost far more
 * than it protects.
 *
 * If component-level tests are ever wanted, this is where the environment
 * would change to jsdom and @angular/build's unit-test builder would earn
 * its place.
 */
export default defineConfig({
  test: {
    include: ['src/**/*.spec.ts'],
    environment: 'node',
    reporters: ['default'],
  },
});
