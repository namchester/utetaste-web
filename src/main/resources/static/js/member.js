'use strict';
document.addEventListener('click',async event=>{
  const button=event.target.closest('[data-favorite]');if(!button||button.disabled)return;
  const selected=button.getAttribute('aria-pressed')!=='true';const restore=UT.busy(button,true,'…');
  try{const result=await UT.request('/api/member/favorites/'+button.dataset.favorite,{selected});restore();button.setAttribute('aria-pressed',String(result.selected));button.textContent=result.selected?'♥':'♡';button.setAttribute('aria-label',(result.selected?'Bỏ yêu thích ':'Yêu thích ')+button.dataset.dishName);UT.toast(result.message);}
  catch(error){restore();UT.toast(error.message,true);}
});
document.addEventListener('submit',event=>{const form=event.target;if(!form.matches('[data-member-form]')||event.defaultPrevented)return;const button=event.submitter;if(button)UT.busy(button,true,'Đang lưu…');});
