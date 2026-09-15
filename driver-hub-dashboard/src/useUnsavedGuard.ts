import { useEffect } from 'react';

/**
 * Asks the browser to confirm before a page holding unsaved work goes away.
 *
 * <p>Registered only while there is something to lose, and removed the moment there is not. That is
 * not tidiness: a {@code beforeunload} listener disqualifies the page from the back/forward cache
 * for as long as it is installed, so one that is always there — even one that decides to allow the
 * navigation — makes every reload and every back button slower for the whole session, in exchange
 * for a prompt that is wanted for a few seconds at a time.</p>
 */
export function useUnsavedGuard(unsaved: boolean): void {
  useEffect(() => {
    if (!unsaved) return;
    // preventDefault is the modern way to ask; browsers word the prompt themselves and ignore any
    // message a page supplies, so there is nothing to say here.
    const confirmLeaving = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', confirmLeaving);
    return () => window.removeEventListener('beforeunload', confirmLeaving);
  }, [unsaved]);
}
