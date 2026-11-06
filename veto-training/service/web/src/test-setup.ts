import '@testing-library/jest-dom/vitest';
import {configure} from '@testing-library/react';
// The required single-CPU Idle runner can delay MUI transitions.
configure({asyncUtilTimeout:5000});
