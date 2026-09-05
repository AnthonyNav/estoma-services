import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const e2eRoot = path.resolve(here, "..");
const collectionPath = path.join(
  e2eRoot,
  "postman",
  "workflows",
  "platform-bff-auth-front-v1.postman_collection.json",
);
const environmentPath = path.join(
  e2eRoot,
  "postman",
  "workflows",
  "platform-bff-auth-front-v1.environment.example.json",
);

const collection = JSON.parse(fs.readFileSync(collectionPath, "utf8"));
const environment = JSON.parse(fs.readFileSync(environmentPath, "utf8"));

const credentialKeys = new Set([
  "normalLoginIdentifier",
  "normalPassword",
  "normalNewPassword",
  "temporaryLoginIdentifier",
  "temporaryPassword",
  "requiredNewPassword",
]);

for (const source of [collection.variable ?? [], environment.values ?? []]) {
  for (const variable of source) {
    if (credentialKeys.has(variable.key) && variable.value !== "") {
      throw new Error(`${variable.key} must remain blank in Git`);
    }
  }
}

if ((collection.variable ?? []).find((entry) => entry.key === "baseUrl")?.value !== "") {
  throw new Error("baseUrl must remain blank in the committed collection");
}
if ((environment.values ?? []).find((entry) => entry.key === "baseUrl")?.value !== "") {
  throw new Error("baseUrl must remain blank in the committed example environment");
}

const collectionVariables = new Map((collection.variable ?? []).map((entry) => [entry.key, entry.value]));
if (collectionVariables.get("revocationPollMaxAttempts") !== "30") {
  throw new Error("revocationPollMaxAttempts must default to 30");
}
if (collectionVariables.get("revocationPollDelayMs") !== "500") {
  throw new Error("revocationPollDelayMs must default to 500");
}

const requests = [];
const scripts = [];
function visit(items) {
  for (const item of items ?? []) {
    for (const event of item.event ?? []) {
      const source = (event.script?.exec ?? []).join("\n");
      scripts.push({ name: item.name, listen: event.listen, source });
    }
    if (item.request) {
      const url = typeof item.request.url === "string" ? item.request.url : item.request.url?.raw;
      requests.push([item.request.method, url]);
    }
    visit(item.item);
  }
}
visit(collection.item);
for (const event of collection.event ?? []) {
  scripts.push({ name: "collection", listen: event.listen, source: (event.script?.exec ?? []).join("\n") });
}

const expected = [
  ["POST", "{{baseUrl}}/api/v1/auth/login"],
  ["GET", "{{baseUrl}}/api/v1/me"],
  ["POST", "{{baseUrl}}/api/v1/me/password"],
  ["POST", "{{baseUrl}}/api/v1/auth/login"],
  ["GET", "{{baseUrl}}/api/v1/me"],
  ["POST", "{{baseUrl}}/api/v1/auth/logout"],
  ["GET", "{{baseUrl}}/api/v1/me"],
  ["POST", "{{baseUrl}}/api/v1/auth/login"],
  ["GET", "{{baseUrl}}/api/v1/me"],
  ["POST", "{{baseUrl}}/api/v1/me/password"],
  ["GET", "{{baseUrl}}/api/v1/me"],
  ["POST", "{{baseUrl}}/api/v1/auth/logout"],
  ["GET", "{{baseUrl}}/api/v1/me"],
];

if (JSON.stringify(requests) !== JSON.stringify(expected)) {
  throw new Error(`Unexpected authentication request sequence: ${JSON.stringify(requests)}`);
}

for (const script of scripts) {
  try {
    new vm.Script(`(function () {\n${script.source}\n})`);
  } catch (error) {
    throw new Error(`Invalid ${script.listen} script in ${script.name}: ${error.message}`);
  }
}

const revocationPollScripts = scripts.filter(({ source }) => source.includes("RevocationPollAttempt"));
if (revocationPollScripts.length !== 4) {
  throw new Error("Both authentication workflows must initialize and execute bounded revocation polling");
}
const revocationPollExecutors = revocationPollScripts.filter(({ source }) => source.includes("setNextRequest"));
if (revocationPollExecutors.length !== 2) {
  throw new Error("Both authentication workflows must execute revocation polling");
}
for (const script of revocationPollExecutors) {
  if (!script.source.includes("attempt < maxAttempts") || !script.source.includes("setTimeout")) {
    throw new Error(`Unbounded revocation polling in ${script.name}`);
  }
}

const serialized = `${JSON.stringify(collection)}\n${JSON.stringify(environment)}`;
if (/https?:\/\/(?!schema\.getpostman\.com)/i.test(serialized)) {
  throw new Error("A concrete HTTP host was found in the committed workflow artifacts");
}
if (/Bearer\s+[A-Za-z0-9._~-]{12,}/i.test(serialized)) {
  throw new Error("A concrete bearer token was found in the committed workflow artifacts");
}

console.log(`Validated Front V1 auth workflow: ${requests.length} request definitions, ${scripts.length} scripts, bounded revocation polling, blank private inputs.`);
