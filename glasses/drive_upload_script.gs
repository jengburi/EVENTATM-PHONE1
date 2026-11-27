// 안경 AI 앱 → 구글 드라이브 업로드 창구 (Google Apps Script)
// 설치: script.google.com → 새 프로젝트 → 이 코드 전체 붙여넣기 → SECRET 값 정하기
//       → 배포 → 새 배포 → 유형: 웹 앱 → 실행: 나 / 액세스: 모든 사용자 → 배포 → 웹 앱 URL 복사
// 앱의 '드라이브 업로드 주소'에 URL, '비밀 코드'에 SECRET 값을 넣으면 됨.

const SECRET = '여기에_비밀코드';
const ROOT = '안경 촬영';

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    if (body.key !== SECRET) return out({ ok: false, error: '비밀 코드가 달라요' });
    const day = Utilities.formatDate(new Date(), 'Asia/Seoul', 'yyyy-MM-dd');
    const folder = getFolder(getFolder(DriveApp.getRootFolder(), ROOT), day);
    let file;
    if (body.text != null) {
      file = folder.createFile(body.name, body.text, MimeType.PLAIN_TEXT);
    } else {
      const blob = Utilities.newBlob(Utilities.base64Decode(body.data), body.mime, body.name);
      file = folder.createFile(blob);
    }
    return out({ ok: true, id: file.getId(), url: file.getUrl() });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  }
}

function doGet() {
  return out({ ok: true, msg: '안경 업로드 창구가 동작 중이에요' });
}

function getFolder(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
