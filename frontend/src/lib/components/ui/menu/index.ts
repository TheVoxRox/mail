/*
 * `focusFirstMenuItem` is deliberately NOT re-exported. It is the handler
 * MenuContent installs, and the whole point of that component is that a menu
 * cannot be written without it — leaving the function reachable would keep the
 * hand-wired path open, which is how five menus ended up disagreeing about
 * whether they had it. Import it from './menu.js' if a second panel component
 * ever needs it; nothing outside this folder should.
 */
export { menuContentVariants, menuItemVariants } from './menu.js';
export { default as MenuContent } from './menu-content.svelte';
export { default as MenuSubContent } from './menu-sub-content.svelte';
