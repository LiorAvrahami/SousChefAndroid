package com.lioravrahami.souschef.ui

/**
 * Stable identifiers for the controls that the on-device smoke tests drive
 * (`Modifier.testTag(TestTags.X)`). They have no effect on what the user sees.
 * Every tag listed here must be applied to exactly the control it describes.
 */
object TestTags {
    // ---- recipe list
    /** "New recipe" floating button. */
    const val RECIPES_NEW = "recipes_new"
    /** Settings action in the list's top bar. */
    const val RECIPES_SETTINGS = "recipes_settings"
    /** "Resume" button of the cooking-in-progress banner. */
    const val RECIPES_RESUME = "recipes_resume"
    /** Each recipe card (also findable by the recipe name text inside it). */
    const val RECIPE_CARD = "recipe_card"

    // ---- recipe editor
    /** Recipe name text field. */
    const val EDITOR_NAME = "editor_name"
    /** "Add step" button. */
    const val EDITOR_ADD_STEP = "editor_add_step"
    /** "Add wait" button. */
    const val EDITOR_ADD_WAIT = "editor_add_wait"
    /** "Save" button of the editor. */
    const val EDITOR_SAVE = "editor_save"
    /** Text field of the text-step editor. */
    const val STEP_TEXT_FIELD = "step_text_field"
    /** Confirm ("Done") button of the text-step editor. */
    const val STEP_DONE = "step_done"
    /** Label field of the wait-step editor. */
    const val WAIT_LABEL_FIELD = "wait_label_field"
    /** Confirm ("Done") button of the wait-step editor. */
    const val WAIT_DONE = "wait_done"
    /** The egg-timer dial canvas. */
    const val DIAL = "dial"
    /** Fine-adjust "+" button under the dial. */
    const val DIAL_PLUS = "dial_plus"
    /** Fine-adjust "−" button under the dial. */
    const val DIAL_MINUS = "dial_minus"
    /** Confirm button of the "name this version" dialog shown when saving changed steps. */
    const val VERSION_NAME_CONFIRM = "version_name_confirm"

    // ---- recipe detail
    /** "Cook the best so far" button. */
    const val COOK_BEST = "cook_best"
    /** "Explore — try a tweak" button. */
    const val COOK_EXPLORE = "cook_explore"
    /** "Classical tweak" option of the explore chooser. */
    const val EXPLORE_CLASSICAL = "explore_classical"
    /** "AI suggestion" option of the explore chooser. */
    const val EXPLORE_AI = "explore_ai"
    /** "Cook this" button of the proposal sheet. */
    const val PROPOSAL_COOK = "proposal_cook"
    /** "Another suggestion" button of the proposal sheet. */
    const val PROPOSAL_ANOTHER = "proposal_another"
    /** "Cancel" button of the proposal sheet. */
    const val PROPOSAL_CANCEL = "proposal_cancel"
    /** Edit action in the recipe detail top bar. */
    const val RECIPE_EDIT = "recipe_edit"

    // ---- cooking
    /** The horizontal pager holding one page per step. */
    const val COOK_PAGER = "cook_pager"
    /** "Next" button on a step page. */
    const val COOK_NEXT = "cook_next"
    /** Button that opens the overview sheet (same as swiping down). */
    const val COOK_OVERVIEW = "cook_overview"
    /** The countdown clock text of a wait page. */
    const val WAIT_COUNTDOWN = "wait_countdown"
    /** "Skip wait" button. */
    const val WAIT_SKIP = "wait_skip"
    /** "Stop alarm & continue" button shown when the time is up. */
    const val WAIT_STOP_ALARM = "wait_stop_alarm"
    /** "Rate this cooking" / "Finish & rate" button on the final page. */
    const val COOK_FINISH_RATE = "cook_finish_rate"
    /** "Exit without rating" button on the final page. */
    const val COOK_EXIT = "cook_exit"

    // ---- rating
    /** Overall score slider. */
    const val RATING_OVERALL = "rating_overall"
    /** Notes text field. */
    const val RATING_NOTES = "rating_notes"
    /** "Save rating" button. */
    const val RATING_SAVE = "rating_save"

    // ---- settings and trash
    /** OpenRouter API key field. */
    const val SETTINGS_API_KEY = "settings_api_key"
    /** "Trash" button. */
    const val SETTINGS_TRASH = "settings_trash"
    /** "Export backup file" button. */
    const val SETTINGS_EXPORT = "settings_export"
    /** "Import backup file" button. */
    const val SETTINGS_IMPORT = "settings_import"
    /** Field where the user types "delete" to confirm permanent deletion. */
    const val TRASH_CONFIRM_FIELD = "trash_confirm_field"
    /** The destructive confirm button, enabled only once "delete" is typed. */
    const val TRASH_CONFIRM_BUTTON = "trash_confirm_button"
}
