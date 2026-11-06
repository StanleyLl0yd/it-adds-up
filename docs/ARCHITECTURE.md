# Архитектура

Один Android app-модуль, Kotlin, Compose, Material 3. Пакеты domain/data/ui/monetization изолируют арифметику от SDK. Для локальных данных будут Room и DataStore. Минимум Android 8.0 (API 26). Деньги — целые копейки и BigDecimal с HALF_UP. Не добавлять backend без необходимости.
