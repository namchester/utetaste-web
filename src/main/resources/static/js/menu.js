'use strict';
(() => {
  const root=document.getElementById('menu-app');if(!root)return;
  const session=root.dataset.session, storageKey='utetaste:cart:'+session+(root.dataset.member?':member:'+root.dataset.member:'');
  const cards=[...root.querySelectorAll('.dish-card')], menu=new Map(cards.map(el=>[Number(el.dataset.id),{id:Number(el.dataset.id),name:el.dataset.name,price:Number(el.dataset.price),available:el.dataset.available==='true'}]));
  let cart=[], requestKey=null, category='all', pending=false;
  const note=document.getElementById('order-note');
  try {const stored=JSON.parse(localStorage.getItem(storageKey)||'{}');cart=(stored.items||[]).filter(x=>menu.has(x.dishId)&&Number.isInteger(x.quantity)&&x.quantity>0&&x.quantity<=20);requestKey=stored.requestKey||null;note.value=String(stored.note||'').slice(0,500);} catch(_) {cart=[];}
  function key() {
    if(crypto.randomUUID)return crypto.randomUUID();
    const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
    const h=[...bytes].map(x=>x.toString(16).padStart(2,'0')).join('');return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);
  }
  function save(changed=false){if(changed)requestKey=null;try{localStorage.setItem(storageKey,JSON.stringify({items:cart,requestKey,note:note.value}));}catch(_){}}
  function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;}
  function render(){
    const focused = document.activeElement;
    const focusedId = focused?.dataset.cartId, focusedAction = focused?.dataset.cartAction;
    const list=document.getElementById('cart-items');list.replaceChildren();let total=0;
    if(!cart.length)list.append(el('div','cart-empty','Chọn vài món ngon để bắt đầu bữa ăn của bạn.'));
    for(const line of cart){const dish=menu.get(line.dishId);total+=dish.price*line.quantity;const row=el('div','cart-item'),top=el('div','cart-item-top');top.append(el('strong','',dish.name),el('span','',UT.money(dish.price*line.quantity)));row.append(top);const ctl=el('div','quantity-control');
      for(const [label,delta] of [['−',-1],['+',1]]){const b=el('button','',label);b.type='button';b.dataset.cartId=line.dishId;b.dataset.cartAction=delta>0?'increase':'decrease';b.setAttribute('aria-label',(delta>0?'Tăng':'Giảm')+' số lượng '+dish.name);b.disabled=pending;b.addEventListener('click',()=>{if(pending)return;if(delta>0&&line.quantity>=20){UT.toast('Mỗi món tối đa 20 phần trong một lượt.',true);return;}line.quantity+=delta;cart=cart.filter(x=>x.quantity>0);save(true);render();});ctl.append(b);if(delta<0)ctl.append(el('span','',String(line.quantity)));}
      const remove=el('button','remove','Xóa');remove.type='button';remove.dataset.cartId=line.dishId;remove.dataset.cartAction='remove';remove.setAttribute('aria-label','Xóa '+dish.name+' khỏi giỏ');remove.disabled=pending;remove.addEventListener('click',()=>{if(pending)return;cart=cart.filter(x=>x!==line);save(true);render();});ctl.append(remove);row.append(ctl);list.append(row);
    }
    document.getElementById('cart-total').textContent=UT.money(total);
    const submit=document.getElementById('submit-order');submit.disabled=!cart.length||pending||root.dataset.open!=='true';submit.textContent=pending?'Đang gửi món…':'Gửi món xuống bếp →';
    submit.setAttribute('aria-busy',String(pending));
    cards.forEach(card => {card.querySelector('[data-add]').disabled=pending||card.dataset.available!=='true'||root.dataset.open!=='true';});
    const preview=document.querySelector('.mobile-cart-link');
    if(preview){preview.hidden=!cart.length;document.getElementById('cart-preview-count').textContent=cart.reduce((sum,line)=>sum+line.quantity,0);document.getElementById('cart-preview-total').textContent=UT.money(total);}
    note.disabled=pending;
    if(focusedId && focusedAction){const next=list.querySelector('[data-cart-id="'+CSS.escape(focusedId)+'"][data-cart-action="'+CSS.escape(focusedAction)+'"]');(next||list).focus({preventScroll:true});}
  }
  document.addEventListener('click',e=>{
    const add=e.target.closest('[data-add]');if(add&&!pending){const id=Number(add.dataset.add),dish=menu.get(id);if(!dish?.available||root.dataset.open!=='true')return;let line=cart.find(x=>x.dishId===id);if(line&&line.quantity>=20){UT.toast('Mỗi món tối đa 20 phần trong một lượt.',true);return;}if(line)line.quantity++;else cart.push({dishId:id,quantity:1});save(true);render();UT.toast('Đã thêm '+dish.name);}
    const tab=e.target.closest('[data-category].category-tab');if(tab){category=tab.dataset.category;document.querySelectorAll('.category-tab').forEach(x=>{x.classList.toggle('active',x===tab);x.setAttribute('aria-pressed',String(x===tab));});filter();}
  });
  function filter(){const q=document.getElementById('menu-search').value.trim().toLocaleLowerCase('vi');let count=0;for(const card of cards){const show=(category==='all'||category===card.dataset.category)&&card.dataset.name.toLocaleLowerCase('vi').includes(q);card.hidden=!show;if(show)count++;}document.getElementById('no-dishes').hidden=count>0;const result=document.getElementById('menu-result-count');if(result)result.textContent=count+' món phù hợp';}
  document.getElementById('menu-search').addEventListener('input',filter);note.addEventListener('input',()=>save(true));
  document.getElementById('submit-order').addEventListener('click',async()=>{
    if(!cart.length||pending)return;pending=true;if(!requestKey)requestKey=key();save();render();
    try{
      await UT.request('/api/guest/orders',{requestKey,items:cart,note:note.value});
      // A browser storage restriction must not turn an accepted order into a failed UI state.
      try{localStorage.removeItem(storageKey);}catch(_){}
      location.assign('/guest/orders');
    }
    catch(e){UT.toast(e.message,true);pending=false;render();}
  });
  const pickUrl=new URL(location.href), pick=Number(pickUrl.searchParams.get('pick'));
  if(pickUrl.searchParams.has('pick')){
    pickUrl.searchParams.delete('pick');history.replaceState(null,'',pickUrl.pathname+pickUrl.search+pickUrl.hash);
    if(menu.get(pick)?.available&&root.dataset.open==='true'){
      const line=cart.find(x=>x.dishId===pick);if(!line)cart.push({dishId:pick,quantity:1});
      save(true);UT.toast('Đã chọn món yêu thích. Kiểm tra giỏ rồi gửi xuống bếp.');
    }
  }
  render();filter();
})();
