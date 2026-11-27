const introduction = "대체 숙소 후보를 안내드립니다. 표시된 금액은 검색 시점의 전체 숙박 요금입니다.";
const reminder = "예약은 진행되지 않았습니다. 실제 예약 가능 여부와 최종 요금은 숙소 링크에서 확인해 주세요.";

type AirbnbGuideItem = { name: string; price: string; url: string; highlight: string };

function listingUrl(value: string): string | null {
  try {
    const url = new URL(value);
    const domains = ["airbnb.com", "airbnb.co.kr"];
    return url.protocol === "https:" && !url.username && !url.password &&
      domains.some(domain => url.hostname === domain || url.hostname.endsWith("." + domain))
      ? url.href : null;
  } catch {
    return null;
  }
}

export function parseAirbnbGuide(body: string) {
  const blocks = body.replace(/\r\n/g, "\n").split("\n\n");
  if (blocks.length !== 5 || blocks[0] !== introduction || blocks[4] !== reminder) return null;

  const items: AirbnbGuideItem[] = [];
  for (const [index, block] of blocks.slice(1, 4).entries()) {
    const lines = block.split("\n");
    const title = /^(\d+)\. (.+) · ([\d,]+원)$/.exec(lines[0]);
    const url = listingUrl(lines[1] ?? "");
    if (lines.length > 3 || !title || Number(title[1]) !== index + 1 || !url) return null;
    items.push({ name: title[2], price: title[3], url, highlight: lines[2] ?? "" });
  }
  return { introduction, reminder, items };
}
