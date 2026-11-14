// Runtime access is separate from retained bytes/evidence. Automatic auth loss
// only fences and closes. Destruction requires explicit consent for one captured
// origin/account/incarnation; a late completion cannot target its successor.
export function createAccountAccess({origin,openStore,onFence,canPurge=()=>true}){
  let active=null,inactive=null,generation=0;
  function deactivate(message='',expected=active,{close=true}={}){
    if(!expected||expected!==active)return false;
    active=null;inactive=expected;generation++;
    try{onFence(message,expected);}finally{if(close)expected.store.close();}
    return true;
  }
  return {
    current:()=>active,
    beginAdmission(){if(active)deactivate();inactive=null;return ++generation;},
    admit({accountId,incarnation},store,ticket){
      if(ticket!==generation){store.close();throw new Error('Account admission changed');}
      if(typeof accountId!=='string'||!accountId||typeof incarnation!=='string'||!incarnation){store.close();throw new Error('Validated account admission required');}
      active=Object.freeze({origin,accountId,incarnation,store});inactive=null;return active;
    },
    deactivate,
    broadcast(message){
      if(!active||!['logout','session-changed'].includes(message?.type)||message.origin!==origin||message.accountId!==active.accountId||message.incarnation!==active.incarnation)return false;
      return deactivate('Sign in again');
    },
    async purgeConfirmed(expected,{confirmed=false,message='Signed out'}={}){
      if(!confirmed||!expected||expected.origin!==origin)return false;
      if(!canPurge(expected)){if(active===expected)deactivate('Sign in again');return false;}
      if(active===expected){
        deactivate(message,expected,{close:false});
        try{await expected.store.retire();}finally{expected.store.close();}
        return true;
      }
      // A logout response may be lost after an automatic 401 closed this exact
      // handle. Reopen only for its confirmed purge, never to grant local access.
      if(active||inactive!==expected)return false;
      const version=generation,store=await openStore(expected.accountId);
      if(version!==generation||active||inactive!==expected||!canPurge(expected)){store.close();return false;}
      try{await store.retire();}finally{store.close();}
      return true;
    }
  };
}
