# DeepSeek implementation unit U4 — large-font navigation rail

GPT design decision from actual device QA: at Android fontScale 1.5, the current 152dp navigation rail forces the Korean label '함께 짠 일정' into a single trailing syllable ('정') on its second line. Main audio controls remain visible. Preserve the label and font scaling, but provide 192dp rail width when configuration.fontScale >= 1.3; use the existing 152dp width below that threshold. This is an adaptive layout rule, not a change to domain behavior.

Implement only that minimal layout change in app/src/main/java/kr/cabinstory/MainActivity.java after reading its render() caller and rail layout. Do not change other files, tests, labels, app state, permissions, density scaling, font sizes, or navigation behavior. No abstraction, dependency or refactor is needed. Do not run tests, browse, create agents or inspect private folders. GPT will rerun the actual 1.5-scale screenshot and build.

Return exact changed path and concise implementation explanation. Use the actual DeepSeek model selected by the command; do not silently switch models.
