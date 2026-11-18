#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
war=modules/kitchensink/target/kitchensink.war
if [[ ! -f "$war" ]]; then
  echo "Missing $war. Run mvn clean package from the repository root." >&2
  exit 1
fi

ctx=$(mktemp -d "${TMPDIR:-/tmp}/migrate-kitchensink-build.XXXXXX")
trap 'rm -rf "$ctx"' EXIT
wildfly_version=28.0.1.Final
archive=${KS_WILDFLY_ZIP:-$ctx/wildfly-$wildfly_version.zip}
if [[ -z "${KS_WILDFLY_ZIP:-}" ]]; then
  curl -fL --retry 2 -o "$archive" "https://github.com/wildfly/wildfly/releases/download/$wildfly_version/wildfly-$wildfly_version.zip"
fi
printf '%s  %s\n' 8000605d800fcb003bac939ee25971b051775df6eb766d8e8de2c0e52dc071fe "$archive" | shasum -a 256 -c -

mkdir "$ctx/legacy" "$ctx/h2"
unzip -q "$archive" -d "$ctx/legacy"
mv "$ctx/legacy/wildfly-$wildfly_version" "$ctx/legacy/wildfly"
cp "$ctx/legacy/wildfly/modules/system/layers/base/com/h2database/h2/main/h2-2.1.214.jar" "$ctx/h2/h2.jar"

# Apply the B1 datasource overlay to the exploded WAR in the image build context.
unzip -q "$war" -x WEB-INF/classes/import.sql -d "$ctx/legacy/app"
datasource="$ctx/legacy/app/WEB-INF/kitchensink-quickstart-ds.xml"
persistence="$ctx/legacy/app/WEB-INF/classes/META-INF/persistence.xml"
if ! grep -Fq 'jdbc:h2:mem:kitchensink-quickstart;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1' "$datasource" ||
   ! grep -Fq 'value="create-drop"' "$persistence"; then
  echo 'The WAR does not contain the baseline H2 datasource and persistence settings.' >&2
  exit 1
fi
sed -e 's|jdbc:h2:mem:kitchensink-quickstart;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1|jdbc:h2:tcp://h2:9092/kitchensink|' \
    -e 's|<user-name>sa</user-name>|<user-name>members</user-name>|' \
    -e 's|<password>sa</password>|<password>__H2_APP_PASSWORD__</password>|' \
    -e 's|</security>|</security><validation><check-valid-connection-sql>SELECT 1</check-valid-connection-sql><validate-on-match>true</validate-on-match></validation>|' \
    "$datasource" > "$datasource.new"
grep -Fq '__H2_APP_PASSWORD__' "$datasource.new"
mv "$datasource.new" "$datasource"
sed 's/value="create-drop"/value="validate"/' "$persistence" > "$persistence.new"
mv "$persistence.new" "$persistence"

for role in h2 legacy; do
  cp "deploy/images/$role/"* "$ctx/$role/"
  repository="migrate-kitchensink-$role"
  docker build --provenance=false --build-arg SOURCE_DATE_EPOCH=1704067200 \
    -t "$repository:build" "$ctx/$role"
  image_id=$(docker image inspect --format '{{.Id}}' "$repository:build")
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
  ref="$repository:content-${image_id#sha256:}"
  docker tag "$image_id" "$ref"
  printf '%s\t%s\t%s\n' "$role" "$ref" "$image_id" >> "$ctx/images.tsv"
done

mkdir -p .run/deploy
mv "$ctx/images.tsv" .run/deploy/images.tsv
cat .run/deploy/images.tsv
