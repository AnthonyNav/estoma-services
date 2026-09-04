#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..', '..');
const openApiPath = process.argv[2] ?? resolve(root, 'docs/openapi.yaml');
const collectionPath = process.argv[3] ?? resolve(root, 'e2e/postman/platform-bff-api.postman_collection.json');
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options']);

function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    throw new Error(`${path} must contain JSON: ${error.message}`);
  }
}

function requiresIdempotency(operation) {
  return (operation.parameters ?? []).some((parameter) =>
    parameter.in === 'header'
    && parameter.required === true
    && parameter.name.toLowerCase() === 'idempotency-key');
}

function requiresBearer(operation) {
  return Array.isArray(operation.security) && operation.security.some((requirement) => 'bearerAuth' in requirement);
}

function operationKey(method, path) {
  return `${method.toUpperCase()} ${path}`;
}

function collectOpenApi(document) {
  const operations = new Map();
  for (const [path, pathItem] of Object.entries(document.paths ?? {})) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!methods.has(method)) continue;
      operations.set(operationKey(method, path), {
        idempotency: requiresIdempotency(operation),
        bearer: requiresBearer(operation),
      });
    }
  }
  return operations;
}

function postmanPath(request) {
  const url = request.url;
  if (typeof url === 'string') return new URL(url.replace('{{baseUrl}}', 'http://catalog.invalid')).pathname;
  const segments = url?.path ?? [];
  return `/${segments.map((segment) => segment.startsWith(':') ? `{${segment.slice(1)}}` : segment).join('/')}`;
}

function collectPostman(items, operations = new Map()) {
  for (const item of items ?? []) {
    if (item.request) {
      const request = item.request;
      const key = operationKey(request.method, postmanPath(request));
      if (operations.has(key)) throw new Error(`duplicate Postman request: ${key}`);
      const headers = request.header ?? [];
      operations.set(key, {
        idempotency: headers.some((header) => !header.disabled && header.key?.toLowerCase() === 'idempotency-key'),
        bearer: request.auth?.type === 'bearer',
      });
    }
    collectPostman(item.item, operations);
  }
  return operations;
}

const openApi = collectOpenApi(readJson(openApiPath));
const postman = collectPostman(readJson(collectionPath).item);
const errors = [];
for (const key of openApi.keys()) if (!postman.has(key)) errors.push(`missing from collection: ${key}`);
for (const key of postman.keys()) if (!openApi.has(key)) errors.push(`not declared by OpenAPI: ${key}`);
for (const [key, expected] of openApi) {
  const actual = postman.get(key);
  if (!actual) continue;
  if (expected.idempotency !== actual.idempotency) errors.push(`Idempotency-Key mismatch: ${key}`);
  if (expected.bearer !== actual.bearer) errors.push(`bearer security mismatch: ${key}`);
}

if (errors.length) {
  console.error(`OpenAPI/Postman contract gate failed (${errors.length} discrepancy${errors.length === 1 ? '' : 'ies'}):`);
  for (const error of errors) console.error(`- ${error}`);
  process.exit(1);
}
console.log(`OpenAPI/Postman contract gate passed: ${openApi.size}/${postman.size} operations; Idempotency-Key and bearer requirements match.`);
