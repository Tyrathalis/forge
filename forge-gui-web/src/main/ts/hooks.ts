// Small pieces of page behaviour that several pages share

import { useEffect, useState } from 'preact/hooks';
import type { RefObject } from 'preact';

/** The value after it has stopped changing for ms. A long list costs a request per row, so a search waits for a pause in typing. */
export function useDebounced<T>(value: T, ms = 200): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(timer);
  }, [value]);
  return settled;
}

/**
 * Closes a popup on Escape, or on a press outside its root element. A popup inside a dialog passes inDialog: it then hears the
 * press and the key before the dialog does, and Escape closes only the popup.
 */
export function useDismiss(open: boolean, root: RefObject<HTMLElement>, close: () => void, inDialog = false): void {
  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => { if (!root.current?.contains(e.target as Node)) close(); };
    const escape = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (inDialog) e.stopPropagation();
      close();
    };
    document.addEventListener('pointerdown', outside, inDialog);
    document.addEventListener('keydown', escape, inDialog);
    return () => {
      document.removeEventListener('pointerdown', outside, inDialog);
      document.removeEventListener('keydown', escape, inDialog);
    };
  }, [open]);
}

/** A two-press button for actions that cannot be undone. The first press arms it and changes its label. The second press runs act. */
export function useTwoPress(): [armed: boolean, press: (act: () => void) => () => void] {
  const [armed, setArmed] = useState(false);
  return [armed, act => () => {
    if (!armed) {
      setArmed(true);
      return;
    }
    act();
    setArmed(false);
  }];
}
