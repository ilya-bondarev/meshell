# Quota ownership

`src/services/quota/QuotaRules.java` owns remaining percentage, severity, progress clamping and duration units. Preserve its NaN handling and rounding; the cards and settings consume these values and format localized text.

Account limits and reset credits come from OpenAI through `ChatEngine`. Reset requests require explicit user confirmation. Missing limit data is not a zero balance. No quota-derived columns are stored.
