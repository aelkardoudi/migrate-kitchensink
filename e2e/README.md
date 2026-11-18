# Tests Playwright

Ces suites viennent du tag `method-b/01-baseline` (`1b92eab`). `tests/contrat/` vérifie le contrat REST et `tests/e2e/` rejoue l’inscription dans le navigateur.

Depuis la racine du dépôt, avec Node.js 22.18 ou plus récent, installe les dépendances et Chromium.

```sh
npm --prefix e2e ci
npm --prefix e2e exec -- playwright install chromium
```

Avec le cluster kind et les images déjà préparés selon le [guide de déploiement](../deploy/README.md), lance les deux suites.

```sh
npm --prefix e2e test
```

Playwright démarre WildFly et H2 dans `migrate-kitchensink-test`, ouvre le port local `18202` le temps du test et écrit le rapport dans `.run/playwright/playwright.json`. Le namespace de test et son volume restent disponibles pour les exécutions suivantes. Chaque inscription utilise une adresse synthétique unique.

Pour lancer une seule suite, utilise `npm --prefix e2e run test:contrat` ou `npm --prefix e2e run test:e2e`. Pour cibler un déploiement de test déjà accessible sur un autre port, adapte l’URL dans `KS_BASE=http://127.0.0.1:18203/kitchensink/ npm --prefix e2e test`.
