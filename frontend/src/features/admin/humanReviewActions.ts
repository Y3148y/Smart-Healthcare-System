import {ref} from 'vue'
import type {HumanReview, HumanReviewQueueItem} from '../triage/types'
export type ReviewSummary={request:HumanReview;sessionTitle:string;patientStatement:string;riskLevel:string;suggestedDepartment:string|null;safetyTip:string|null;assessmentVersion:number|null;assessedAt:string|null;source:string}
type Request=<T>(path:string,options?:RequestInit)=>Promise<T>

/** Serialize reads and writes; reconcile uncertain writes instead of replaying them. */
export function humanReviewActions(request:Request) {
  const reviews=ref<HumanReviewQueueItem[]>([]),summary=ref<ReviewSummary|null>(null)
  const loading=ref(false),busy=ref(false),ready=ref(false),error=ref(''),notice=ref('')
  const message=(e:unknown)=>e instanceof Error?e.message:'请求失败'
  async function load(){
    if(loading.value||busy.value)return
    loading.value=true;ready.value=false;error.value='';notice.value='';summary.value=null
    try{reviews.value=await request<HumanReviewQueueItem[]>('/admin/human-reviews');ready.value=true}
    catch(e){error.value=`申请列表加载失败：${message(e)}。请刷新申请。`}
    finally{loading.value=false}
  }
  async function view(id:string){
    if(!ready.value||loading.value||busy.value||!reviews.value.some(r=>r.id===id&&r.summaryAccessible))return
    busy.value=true;error.value='';summary.value=null
    try{summary.value=await request<ReviewSummary>(`/admin/human-reviews/${encodeURIComponent(id)}/summary`)}
    catch(e){error.value=message(e)}
    finally{busy.value=false}
  }
  async function change(id:string,status:'ACCEPTED'|'CLOSED'){
    const current=reviews.value.find(r=>r.id===id)
    if(!ready.value||loading.value||busy.value||!current||current.status==='CLOSED'
        ||status==='ACCEPTED'&&current.status!=='PENDING')return
    busy.value=true;error.value='';notice.value=''
    try{
      const updated=await request<HumanReviewQueueItem>(`/admin/human-reviews/${encodeURIComponent(id)}`,{method:'PATCH',body:JSON.stringify({status})})
      reviews.value=reviews.value.map(r=>r.id===id?updated:r)
      summary.value=null;notice.value='申请状态已更新'
    }catch(e){
      ready.value=false;summary.value=null
      error.value=`操作结果待核对：${message(e)}。请刷新申请确认，不会自动重试。`
    }finally{busy.value=false}
  }
  return {reviews,summary,loading,busy,ready,error,notice,load,view,change}
}
