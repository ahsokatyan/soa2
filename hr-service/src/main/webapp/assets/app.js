'use strict';
const config = window.SOA_CONFIG;
const $ = (selector, parent = document) => parent.querySelector(selector);
const positions = ['DIRECTOR', 'MANAGER', 'COOK'];
const statuses = ['FIRED', 'HIRED', 'RECOMMENDED_FOR_PROMOTION', 'REGULAR'];
const colors = ['GREEN', 'RED', 'YELLOW', 'BROWN'];
const titles = {DIRECTOR:'Директор',MANAGER:'Менеджер',COOK:'Повар',FIRED:'Уволен',HIRED:'Нанят',RECOMMENDED_FOR_PROMOTION:'Рекомендован к повышению',REGULAR:'Штатный',GREEN:'Зелёный',RED:'Красный',YELLOW:'Жёлтый',BROWN:'Карий'};
const fields = [
  {key:'id',label:'ID',type:'number',min:'1',max:'2147483647',readonly:true},
  {key:'name',label:'Имя',required:true},
  {key:'coordinates.x',label:'Координата X',integer:true,required:true},
  {key:'coordinates.y',label:'Координата Y',type:'number',min:'-100',max:'2147483647',required:true},
  {key:'creationDate',label:'Дата создания',type:'date',readonly:true},
  {key:'salary',label:'Зарплата',type:'number',min:'0',step:'any'},
  {key:'startDate',label:'Дата начала работы',type:'date',required:true},
  {key:'position',label:'Должность',options:positions},
  {key:'status',label:'Статус',options:statuses},
  {key:'person.height',label:'Рост',type:'number',min:'0',step:'any',person:true,required:true},
  {key:'person.eyeColor',label:'Цвет глаз',options:colors,person:true,required:true},
  {key:'organizationId',label:'ID организации',positiveInteger:true,required:true}
];
let editingId = null, loading = false, totalPages = 0;
function node(tag, text, className) { const element = document.createElement(tag); if(text !== undefined) element.textContent = text; if(className) element.className = className; return element; }
function notify(message, error = false) { const box = $('#notice'); box.hidden = false; box.textContent = message; box.className = error ? 'error' : ''; }
function report(error) { notify(error.message || String(error), true); $('#field-errors').textContent = error.violations?.map(v => `${v.field}: ${v.message}`).join('\n') || ''; }
function handle(fn) { return async event => { if(event) event.preventDefault(); try { await fn(event); } catch(error) { report(error); } }; }
function child(element, key) { return element ? Array.from(element.children).find(item => item.localName === key) : undefined; }
function textAt(element, path) { for(const key of path.split('.')) element = child(element, key); return element?.textContent || ''; }
function display(element, field) { const value = textAt(element, field.key); return value ? titles[value] || value : '—'; }
async function api(base, path, method = 'GET', body) {
  let response;
  try { response = await fetch(base + path, {method,headers:{Accept:'application/xml',...(body ? {'Content-Type':'application/xml'} : {})},body,signal:AbortSignal.timeout(15000)}); }
  catch(error) { throw new Error('Сервис недоступен. Проверьте запуск серверов и разрешите их самоподписанные сертификаты. ' + (error.name === 'TimeoutError' ? 'Превышено время ожидания.' : '')); }
  if(response.status === 204) return null;
  const xml = new DOMParser().parseFromString(await response.text(), 'application/xml');
  if(xml.querySelector('parsererror')) throw new Error(`Сервис вернул некорректный XML (HTTP ${response.status})`);
  if(!response.ok) {
    const root = xml.documentElement;
    const violations = Array.from(child(root,'violations')?.children || []).map(v => ({field:textAt(v,'field'),message:textAt(v,'message')}));
    const error = new Error(`HTTP ${response.status}: ${textAt(root,'message') || response.statusText}`); error.violations = violations; throw error;
  }
  return xml.documentElement;
}
function inputFor(field, prefix, filter) {
  const label = node('label', field.label + (!filter && field.required ? ' *' : ''));
  const input = node(field.options ? 'select' : 'input'); input.id = prefix + field.key; input.name = field.key;
  if(field.options) {
    const empty = node('option',filter ? 'Любое значение' : 'Не указано'); empty.value = ''; input.append(empty);
    for(const value of field.options) { const option = node('option',titles[value]); option.value = value; input.append(option); }
  } else {
    input.type = field.type || 'text';
    for(const key of ['min','max','step']) if(field[key] !== undefined) input.setAttribute(key,field[key]);
    if(field.integer) { input.inputMode='numeric'; input.pattern='-?[0-9]+'; }
    if(field.positiveInteger) { input.inputMode='numeric'; input.pattern='[1-9][0-9]*'; }
    if(field.type === 'date') input.max = '9999-12-31';
  }
  if(!filter && field.required) input.required = true;
  label.append(input); return label;
}
for(const field of fields) {
  $('#filters').append(inputFor(field,'filter-',true));
  $('#worker-head').append(node('th',field.label));
  if(!field.readonly) $(field.person ? '#person-fields' : '#editor-fields').append(inputFor(field,'edit-',false));
}
$('#worker-head').append(node('th','Действия'));
function query() {
  const params = new URLSearchParams(); params.set('page',$('#page').value); params.set('size',$('#size').value);
  for(const field of fields) { const value = document.getElementById('filter-' + field.key).value; if(value !== '') params.set(field.key,value); }
  for(const row of document.querySelectorAll('.sort-row')) params.append('sort',row.children[0].value + ',' + row.children[1].value);
  return params.toString();
}
async function load() {
  if(loading) return; loading = true; $('#refresh').disabled = true;
  try {
    const page = await api(config.workerBase,'/workers?' + query());
    const workers = Array.from(child(page,'workers')?.children || []); const tbody = $('#workers-body'); tbody.replaceChildren();
    for(const worker of workers) {
      const row = node('tr'); const id = textAt(worker,'id');
      for(const field of fields) row.append(node('td',display(worker,field)));
      const actions = node('td',undefined,'actions');
      const edit = node('button','Изменить','secondary'); edit.type = 'button'; edit.addEventListener('click',handle(() => editWorker(id)));
      const remove = node('button','Удалить','danger'); remove.type = 'button'; remove.addEventListener('click',handle(async () => {
        if(!window.confirm(`Удалить работника ${id}?`)) return;
        await api(config.workerBase,'/workers/' + id,'DELETE'); notify(`Работник ${id} удалён`); if(editingId === id) resetEditor(); await load();
      })); actions.append(edit,remove); row.append(actions); tbody.append(row);
    }
    if(!workers.length) { const row = node('tr'); const cell = node('td','Работники не найдены'); cell.colSpan = fields.length + 1; row.append(cell); tbody.append(row); }
    totalPages = Number(textAt(page,'totalPages')); const current = Number(textAt(page,'page'));
    $('#page-info').textContent = `Страница ${current} · всего страниц ${totalPages} · работников ${textAt(page,'totalElements')}`;
    $('#previous').disabled = current <= 1; $('#next').disabled = current >= totalPages;
  } finally { loading = false; $('#refresh').disabled = false; }
}
function addSort() {
  const row = node('div',undefined,'sort-row'); const fieldSelect = node('select'); fieldSelect.setAttribute('aria-label','Поле сортировки');
  for(const field of fields) { const option = node('option',field.label); option.value = field.key; fieldSelect.append(option); }
  const direction = node('select'); direction.setAttribute('aria-label','Направление сортировки');
  for(const [value,title] of [['asc','По возрастанию'],['desc','По убыванию']]) { const option = node('option',title); option.value = value; direction.append(option); }
  const remove = node('button','Убрать','secondary'); remove.type = 'button'; remove.addEventListener('click',() => row.remove()); row.append(fieldSelect,direction,remove); $('#sorts').append(row);
}
function resetEditor() {
  editingId = null; $('#worker-form').reset(); $('#person-fields').disabled = true; $('#editor-title').textContent = 'Новый работник'; $('#save-worker').textContent = 'Создать'; $('#field-errors').textContent = '';
}
async function editWorker(id) {
  const worker = await api(config.workerBase,'/workers/' + id); resetEditor(); editingId = id;
  for(const field of fields) if(!field.readonly) document.getElementById('edit-' + field.key).value = textAt(worker,field.key);
  $('#has-person').checked = Boolean(child(worker,'person')); $('#person-fields').disabled = !$('#has-person').checked;
  $('#editor-title').textContent = `Редактирование работника ${id}`; $('#save-worker').textContent = 'Сохранить'; $('#worker-form').scrollIntoView({behavior:'smooth',block:'center'});
}
function workerXml() {
  const doc = document.implementation.createDocument(null,'worker');
  for(const field of fields) {
    if(field.readonly || (field.person && !$('#has-person').checked)) continue;
    const value = document.getElementById('edit-' + field.key).value; if(value === '') continue;
    const parts = field.key.split('.'); let parent = doc.documentElement;
    for(const key of parts.slice(0,-1)) { let nested = child(parent,key); if(!nested) { nested=doc.createElement(key); parent.append(nested); } parent=nested; }
    const item = doc.createElement(parts.at(-1)); item.textContent = value; parent.append(item);
  }
  return new XMLSerializer().serializeToString(doc);
}
$('#worker-form').addEventListener('submit',handle(async () => {
  $('#field-errors').textContent = ''; const id = editingId;
  const worker = await api(config.workerBase,'/workers' + (id ? '/' + id : ''),id ? 'PUT' : 'POST',workerXml());
  resetEditor(); notify(`Работник ${textAt(worker,'id')} ${id ? 'обновлён' : 'создан'}`); await load();
}));
$('#has-person').addEventListener('change',() => { $('#person-fields').disabled = !$('#has-person').checked; });
$('#cancel-edit').addEventListener('click',resetEditor);
$('#refresh').addEventListener('click',handle(load)); $('#query-form').addEventListener('submit',handle(load)); $('#add-sort').addEventListener('click',addSort);
$('#reset-query').addEventListener('click',handle(async () => { $('#query-form').reset(); $('#sorts').replaceChildren(); await load(); }));
$('#previous').addEventListener('click',handle(async () => { $('#page').value = Math.max(1,Number($('#page').value)-1); await load(); }));
$('#next').addEventListener('click',handle(async () => { $('#page').value = Number($('#page').value)+1; await load(); }));
$('#lookup-form').addEventListener('submit',handle(async event => {
  const id = new FormData(event.target).get('id'); const result = $('#lookup-result'); result.replaceChildren(); const worker = await api(config.workerBase,'/workers/' + id);
  const list = node('dl'); for(const field of fields) list.append(node('dt',field.label),node('dd',display(worker,field))); result.append(list);
  const edit = node('button','Редактировать','secondary'); edit.type='button'; edit.addEventListener('click',handle(() => editWorker(id))); result.append(edit);
}));
$('#salary-sum').addEventListener('click',handle(async () => { const result=await api(config.workerBase,'/workers/salary/sum'); $('#salary-result').textContent='Сумма зарплат: ' + textAt(result,'value'); }));
$('#salary-unique').addEventListener('click',handle(async () => { const result=await api(config.workerBase,'/workers/salary/unique'); const values=Array.from(result.children).map(s => s.textContent); $('#salary-result').textContent='Уникальные зарплаты: ' + (values.join(', ') || 'нет значений'); }));
$('#count-form').addEventListener('submit',handle(async event => { const value=new FormData(event.target).get('value'); const result=await api(config.workerBase,'/workers/salary/count-less-than?value=' + encodeURIComponent(value)); $('#salary-result').textContent='Работников с зарплатой меньше порога: ' + textAt(result,'value'); }));
$('#move-form').addEventListener('submit',handle(async event => {
  const data=new FormData(event.target); const worker=await api(config.hrBase,`/hr/move/${encodeURIComponent(data.get('worker'))}/${encodeURIComponent(data.get('from'))}/${encodeURIComponent(data.get('to'))}`,'POST');
  notify(`Работник ${textAt(worker,'id')} переведён в организацию ${textAt(worker,'organizationId')}`); await load();
}));
$('#index-form').addEventListener('submit',handle(async event => {
  const data=new FormData(event.target); const result=await api(config.hrBase,`/hr/index/all/${encodeURIComponent(data.get('org'))}/${encodeURIComponent(data.get('coeff'))}`,'POST');
  notify(`Индексация организации ${textAt(result,'organizationId')} завершена. Обновлено работников: ${textAt(result,'updatedWorkers')}`); await load();
}));
$('#worker-certificate').href=config.workerBase + '/workers'; $('#connection-info').textContent='Worker API: ' + config.workerBase + ' · HR API: ' + location.origin + config.hrBase + '/hr';
handle(load)();
