# Rapport de qualification — Kitchensink legacy 8.0.0.GA

**Résultat fonctionnel : 13 tests réussis, 0 échec, 0 ignoré.** Exécution le 18 novembre 2026, de 16 h 31 à 16 h 34 (heure de Paris).

Le WAR a été reconstruit depuis l’instantané des sources de `migrate-kitchensink`. Les images ont ensuite été construites à partir de ce build et déployées dans un nouveau namespace, `migrate-kitchensink-qual-20261118-163145`, sur le cluster `kind-kitchensink`. WildFly 28.0.1.Final et H2 2.1.214 ont démarré. La base contenait un membre initial avant les tests.

| Vérification | Résultat | Preuve |
| --- | --- | --- |
| Build Maven `verify` et Checkstyle | Réussis | [Journal Maven](maven-verify.log) |
| Vérification TypeScript | Réussie | [Journal TypeScript](playwright-types.log) |
| IT Java sur le déploiement Kubernetes | 1/1, inscription REST avec Maven Failsafe | [Journal Failsafe](maven-it.log) |
| Contrat REST Playwright | 11/11, liste, tri, homonymes, détail, validation, doublons et concurrence | [Rapport HTML](playwright-html/index.html) |
| E2E Playwright sur Chromium | 1/1, inscription JSF, affichage dans la liste et accès au détail | [Rapport JSON](playwright.json) |

Les tests de performance sont reportés. `MemberRegistrationIT` avec Arquillian déploie un WAR de test distinct. Le test d’intégration de cette qualification cible le WAR applicatif déployé sur Kubernetes via `RemoteMemberRegistrationIT`.

Les [empreintes des sources au moment du test](source.sha256), le [SHA-256 du WAR testé](war.sha256) et les [empreintes des preuves](artifacts.sha256) identifient cette qualification. Le WAR peut être reconstruit avec `mvn clean package`. Les [références d’images Docker](images.tsv) sont reliées aux [images des pods observées](kubernetes-images.txt).

Le dossier de travail n’avait pas encore de métadonnées Git lors de la qualification. Le README racine a été complété après la qualification. Le manifeste `source.sha256` conserve l’empreinte du README tel qu’il était au moment du test.
