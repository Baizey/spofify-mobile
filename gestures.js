// Pointer-down binding is immutable. Progress may move, but an occurrence or
// navigation change cancels the gesture instead of retargeting its release.
export function attachSwipe(element,{binding,valid=()=>true,onCommit,axis='x',threshold=72}={}){
  if(!element)return()=>{};
  let active=null;
  const reset=()=>{active=null;element.style.transform='';element.classList.remove('gesture-active');};
  const down=event=>{
    if(active){reset();return;}
    if(!event.isPrimary||event.button!==0||event.target.closest('input,select,a,button:not(.row-body)'))return;
    active={id:event.pointerId,x:event.clientX,y:event.clientY,binding:binding(),direction:0,locked:false,invalid:false};
  };
  const move=event=>{
    if(!active||event.pointerId!==active.id)return;
    if(!valid(active.binding)){reset();return;}
    const dx=event.clientX-active.x,dy=event.clientY-active.y,along=axis==='x'?dx:dy,cross=axis==='x'?dy:dx;
    if(!active.locked){if(Math.abs(cross)>12&&Math.abs(cross)>Math.abs(along)){reset();return;}if(Math.abs(along)<12)return;active.locked=true;active.direction=Math.sign(along);element.setPointerCapture?.(event.pointerId);}
    if(Math.sign(along)!==active.direction&&Math.abs(along)>12){reset();return;}
    if(Math.abs(cross)>Math.max(36,Math.abs(along))){reset();return;}
    active.distance=along;element.classList.add('gesture-active');
    if(!matchMedia('(prefers-reduced-motion: reduce)').matches)element.style.transform=axis==='x'?`translateX(${Math.max(-110,Math.min(110,along))}px)`:`translateY(${Math.max(0,Math.min(160,along))}px)`;
    event.preventDefault();
  };
  const up=event=>{
    if(!active||event.pointerId!==active.id)return;
    const gesture=active,commit=gesture.locked&&Math.abs(gesture.distance||0)>=threshold&&valid(gesture.binding);
    reset();
    if(commit){event.preventDefault();event.stopPropagation();const suppress=e=>{e.preventDefault();e.stopImmediatePropagation();element.removeEventListener('click',suppress,true);};element.addEventListener('click',suppress,{once:true,capture:true});queueMicrotask(()=>setTimeout(()=>element.removeEventListener('click',suppress,true),0));onCommit(axis==='x'?(gesture.distance<0?'left':'right'):(gesture.distance<0?'up':'down'),gesture.binding);}
  };
  const listeners=[['pointerdown',down],['pointermove',move],['pointerup',up],['pointercancel',reset],['lostpointercapture',reset]];
  listeners.forEach(([name,fn])=>element.addEventListener(name,fn,{passive:false}));
  return()=>{reset();listeners.forEach(([name,fn])=>element.removeEventListener(name,fn));};
}
