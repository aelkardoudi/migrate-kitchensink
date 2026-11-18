import { expect, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

const endpoint = 'rest/members';
type Registration = { name: string; email: string; phoneNumber: string };
type Member = Registration & { id: number };

function registration(overrides: Partial<Registration> = {}): Registration {
  return { name: 'Élodie أمينة', email: randomUUID() + '@example.test', phoneNumber: '0123456789', ...overrides };
}

async function create(request: APIRequestContext, member: Registration) {
  const response = await request.post(endpoint, { data: member });
  expect(response.status()).toBe(200);
  expect(await response.body()).toHaveLength(0);
}

async function list(request: APIRequestContext): Promise<Member[]> {
  const response = await request.get(endpoint);
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toMatch(/application\/json/);
  const values = await response.json();
  expect(Array.isArray(values)).toBe(true);
  return values;
}

test('B1-REST-01 · list, typed numeric ID, Unicode, exact detail and a missing ID', async ({ request }) => {
  const data = registration();
  await create(request, data);
  const members = (await list(request)).filter(member => member.email === data.email);
  expect(members).toHaveLength(1);
  const [member] = members;
  expect(member).toEqual({ ...data, id: expect.any(Number) });
  expect(Number.isSafeInteger(member.id)).toBe(true);
  const detail = await request.get(endpoint + '/' + member.id);
  expect(detail.status()).toBe(200);
  expect(detail.headers()['content-type']).toMatch(/application\/json/);
  expect(await detail.json()).toEqual(member);
  expect((await request.get(endpoint + '/9223372036854775807')).status()).toBe(404);
});

test('B1-REST-02 · list sorts names and preserves both IDs for equal names', async ({ request }) => {
  const data = ['Zulu', 'Alpha', 'Alpha'].map(name => registration({ name }));
  for (const value of data) await create(request, value);
  const emails = new Set(data.map(value => value.email));
  const matches = (await list(request)).filter(member => emails.has(member.email));
  expect(matches.map(member => member.name)).toEqual(['Alpha', 'Alpha', 'Zulu']);
  expect(new Set(matches.map(member => member.email))).toEqual(emails);
  expect(new Set(matches.map(member => member.id)).size).toBe(3);
});

test('B1-REST-03 · another registration with an existing email receives 409', async ({ request }) => {
  const value = registration();
  await create(request, value);
  const conflict = await request.post(endpoint, { data: { ...value, name: 'Autre Membre' } });
  expect(conflict.status()).toBe(409);
  expect(await conflict.json()).toEqual({ email: 'Email taken' });
  const matching = (await list(request)).filter(member => member.email === value.email);
  expect(matching).toEqual([{ ...value, id: expect.any(Number) }]);
});

for (const [label, changes, expectedFields] of [
  ['name longer than 25', { name: 'A'.repeat(26) }, ['name']],
  ['empty name', { name: '' }, ['name']],
  ['9 phone digits', { phoneNumber: '012345678' }, ['phoneNumber']],
  ['13 phone digits', { phoneNumber: '1234567890123' }, ['phoneNumber']],
  ['letters in phone', { phoneNumber: '01234ABCDE' }, ['phoneNumber']],
  ['several invalid fields', { name: 'Bad1', email: 'invalid', phoneNumber: 'X' }, ['email', 'name', 'phoneNumber']]
] as const) {
  test('B1-REST-04 · field errors for ' + label, async ({ request }) => {
    const value = registration(changes);
    const invalid = await request.post(endpoint, { data: value });
    expect(invalid.status()).toBe(400);
    const errors = await invalid.json();
    expect(Object.keys(errors).sort()).toEqual([...expectedFields].sort());
    for (const field of expectedFields) expect(errors[field]).toEqual(expect.any(String));
    expect((await list(request)).filter(member => member.email === value.email)).toHaveLength(0);
  });
}

test('B1-REST-05 · accepts a 25-character name and 12 phone digits', async ({ request }) => {
  const value = registration({ name: 'A'.repeat(25), phoneNumber: '012345678901' });
  await create(request, value);
  const matching = (await list(request)).filter(member => member.email === value.email);
  expect(matching).toEqual([{ ...value, id: expect.any(Number) }]);
});

test('B1-REST-06 · concurrent requests for one email persist exactly one member', async ({ request }, info) => {
  const value = registration();
  const responses = await Promise.all([request.post(endpoint, { data: value }), request.post(endpoint, { data: value })]);
  const statuses = responses.map(response => response.status());
  await info.attach('observed concurrent HTTP statuses', { body: JSON.stringify(statuses), contentType: 'application/json' });
  expect(statuses.filter(status => status === 200)).toHaveLength(1);
  expect((await list(request)).filter(member => member.email === value.email)).toEqual([{ ...value, id: expect.any(Number) }]);
});
